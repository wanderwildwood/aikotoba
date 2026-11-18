package com.wanderwildwood.aikotoba.vault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import com.wanderwildwood.aikotoba.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * The vault file wherever it lives: a folder on the phone, Nextcloud's or Syncthing's folder, a
 * Samba share through Files. All the app knows of it is an address another app answers for.
 *
 * Saving is careful in four ways, because the file is the only copy there is:
 *  1. **Changed underneath?** Before writing, the file is read again and compared with what was
 *     opened. If another device saved it meanwhile, nothing is written and the person decides.
 *  2. **The copy before.** What is about to be replaced is kept inside the app first (written to
 *     a temporary name and renamed, so that copy is whole or absent, never half).
 *  3. **Whole, then checked.** The new file is written in one go, flushed to the disk, and read
 *     back; anything but a byte-for-byte match puts the copy from before back.
 *  4. **Interrupted?** A note that a save has begun is made before writing and removed after
 *     the check. If the phone dies between the two, the next open finds the note and offers the
 *     copy from before (see [interrupted]).
 */
class VaultFile(private val context: Context, val uri: Uri) {

    sealed class Saved {
        data class Done(val hash: ByteArray) : Saved()
        /** The file is not what was opened: someone else saved it. Nothing was written. */
        object Changed : Saved()
        /**
         * It could not be written, or did not read back right; [restored] says whether the file
         * is as it was (also when nothing was written at all).
         */
        data class Failed(val reason: String, val restored: Boolean) : Saved()
    }

    /** The whole file, up to [MAX_BYTES]: anything bigger is not a vault, and would not fit in memory. */
    fun read(): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException(context.getString(R.string.vault_no_answer))
        val bytes = try {
            input.use { readAtMost(it, MAX_BYTES + 1) }
        } catch (e: OutOfMemoryError) {
            throw IOException(context.getString(R.string.vault_too_large))
        }
        if (bytes.size > MAX_BYTES) throw IOException(context.getString(R.string.vault_too_large))
        return bytes
    }

    /** What the file is called where it lives, for the screens. */
    fun name(): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()

    /** Whether this app was given the right to write it, and not only to read it. */
    fun writable(): Boolean {
        val persisted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (persisted) return true
        return context.checkCallingOrSelfUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Replace the file with [bytes], if it still is the file whose hash is [expected] (null: replace
     * whatever is there). [putBack]: [bytes] are the copy kept from before, being put back; if that
     * does not go through, the copy is kept again, since it was replaced by the file now there
     * before the write began.
     */
    fun write(bytes: ByteArray, expected: ByteArray?, putBack: Boolean = false): Saved {
        val current = try {
            read()
        } catch (e: Exception) {
            // Nothing written: the file is as it was, and the copy from before must not be
            // offered as the way back, since it is older than the file.
            return Saved.Failed(e.message ?: e.javaClass.simpleName, restored = true)
        }
        if (expected != null && !sha256(current).contentEquals(expected)) return Saved.Changed

        // After a save that was cut short, the file is suspect and the copy kept from before is
        // the good one: it is not replaced by the file now there, whatever this write is (putting
        // that copy back, or a save over the file).
        val suspect = Pending.interrupted(context, uri, current)
        // Another vault's save was cut short and the copy kept is that vault's only good one:
        // this save would replace both the copy and the note, so it waits until that vault is
        // opened again and its copy put back or the file left as it is.
        if (!suspect && Pending.waitingElsewhere(context, uri)) {
            return Saved.Failed(context.getString(R.string.save_waits_for_other), restored = true)
        }
        if (!suspect) try {
            keepPrevious(current)
        } catch (e: Exception) {
            // Without the copy from before, the save does not go ahead: a failure halfway would
            // then have nothing to put back.
            return Saved.Failed(context.getString(R.string.save_keep_failed), restored = true)
        }

        val newHash = sha256(bytes)
        Pending.begin(context, uri, newHash)
        val problem = try {
            writeWhole(bytes)
            if (sha256(read()).contentEquals(newHash)) null else context.getString(R.string.save_read_back_different)
        } catch (e: Exception) {
            if (refusedAsChanged(e)) {
                // The server's file was saved by someone else while this went up, and Files
                // refused to replace it: nothing was written, and there is nothing to put back.
                Pending.end(context)
                if (putBack && !suspect) keepAgain(bytes)
                return Saved.Changed
            }
            e.message ?: e.javaClass.simpleName
        }
        if (problem == null) {
            Pending.end(context)
            return Saved.Done(newHash)
        }
        val restored = runCatching {
            writeWhole(current)
            sha256(read()).contentEquals(sha256(current))
        }.getOrDefault(false)
        // Put back and checked: nothing is left half done. Not put back: the note stays, and the
        // next open offers the copy from before.
        if (restored && !suspect) Pending.end(context)
        if (putBack && !suspect) keepAgain(bytes)
        return Saved.Failed(problem, restored)
    }

    /** The copy from before, kept again after a put-back of it did not go through: it is still the way back. */
    private fun keepAgain(bytes: ByteArray) {
        runCatching { keepPrevious(bytes) }
    }

    private fun writeWhole(bytes: ByteArray) {
        // "rwt" truncates; some providers only know "wt".
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "rwt") }.getOrNull()
            ?: context.contentResolver.openFileDescriptor(uri, "wt")
            ?: throw IOException(context.getString(R.string.vault_no_answer))
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                out.write(bytes)
                out.flush()
                // A pipe from a provider that uploads on close cannot be synced; that is its job then.
                runCatching { out.fd.sync() }
            }
        }
    }

    private fun keepPrevious(bytes: ByteArray) {
        val dir = previousDir(context)
        dir.mkdirs()
        val tmp = File(dir, "previous.kdbx.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, PREVIOUS))) throw IOException("rename failed")
        File(dir, PREVIOUS_FROM).writeText(uri.toString())
    }

    companion object {
        /**
         * Files (tana) saves a server's file when the app closes it, after this has returned, so
         * it cannot refuse the write itself. A refusal because the server's file changed is said
         * to the next read instead, in these words.
         */
        private const val CHANGED_ON_THE_SERVER = "changed on the server"

        fun refusedAsChanged(e: Exception): Boolean =
            e is java.io.FileNotFoundException && e.message?.contains(CHANGED_ON_THE_SERVER) == true

        private const val PREVIOUS = "previous.kdbx"
        private const val PREVIOUS_FROM = "previous.from"

        /** Far beyond any real KeePass file, attachments and all. */
        const val MAX_BYTES = 128 * 1024 * 1024

        /** At most [limit] bytes of [input]; a longer stream is cut there. */
        fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun previousDir(context: Context) = File(context.noBackupFilesDir, "previous")

        /** The copy kept from before the last save of the file at [uri], if there is one. */
        fun previous(context: Context, uri: Uri): ByteArray? {
            val dir = previousDir(context)
            val from = runCatching { File(dir, PREVIOUS_FROM).readText() }.getOrNull()
            val file = File(dir, PREVIOUS)
            return if (from == uri.toString() && file.isFile) file.readBytes() else null
        }

        /**
         * Forget the copy from before, when the vault it belongs to, [uri], is closed. A copy kept
         * for another vault stays: after a save of that vault was cut short, it is its only good
         * copy.
         */
        fun forgetPrevious(context: Context, uri: Uri) {
            forgetPreviousIn(previousDir(context), uri.toString())
        }

        /** [forgetPrevious] on the folder [dir], for the vault at [uri]. */
        internal fun forgetPreviousIn(dir: File, uri: String) {
            val from = runCatching { File(dir, PREVIOUS_FROM).readText() }.getOrNull()
            if (from == uri) dir.deleteRecursively()
        }
    }

    /** The note that a save has begun and not yet been checked. */
    object Pending {
        private fun prefs(context: Context) = context.getSharedPreferences("pending", Context.MODE_PRIVATE)

        fun begin(context: Context, uri: Uri, hash: ByteArray) {
            // commit, not apply: the note has to be on disk before the first byte of the file is.
            prefs(context).edit().putString("uri", uri.toString())
                .putString("hash", hash.joinToString("") { "%02x".format(it) }).commit()
        }

        fun end(context: Context) {
            prefs(context).edit().clear().commit()
        }

        /**
         * Whether the note is for another file than [uri], that file's save is still not seen to
         * have finished, and the copy from before kept for it is still here. The other file is
         * read to see: a save that did finish clears the note. A note with no copy left (the
         * vault was closed) offers nothing and is dropped.
         */
        fun waitingElsewhere(context: Context, uri: Uri): Boolean {
            val other = prefs(context).getString("uri", null) ?: return false
            if (other == uri.toString()) return false
            val otherUri = Uri.parse(other)
            if (previous(context, otherUri) == null) {
                end(context)
                return false
            }
            val bytes = runCatching { VaultFile(context, otherUri).read() }.getOrNull()
            return interrupted(context, otherUri, bytes)
        }

        /**
         * Whether the last save of [uri] began and was never seen to finish, and the file is not
         * what it was going to be. A save that did finish clears the note here.
         */
        fun interrupted(context: Context, uri: Uri, current: ByteArray?): Boolean {
            val p = prefs(context)
            if (p.getString("uri", null) != uri.toString()) return false
            val wanted = p.getString("hash", null)
            val now = current?.let { sha256(it).joinToString("") { b -> "%02x".format(b) } }
            if (now != null && now == wanted) {
                end(context)
                return false
            }
            return true
        }
    }
}
