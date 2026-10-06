package com.wanderwildwood.aikotoba.vault

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
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
        /** It could not be written, or did not read back right; [restored] says whether the old file was put back. */
        data class Failed(val reason: String, val restored: Boolean) : Saved()
    }

    fun read(): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("nothing answered for the file")

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

    /** Replace the file with [bytes], if it still is the file whose hash is [expected] (null: replace whatever is there). */
    fun write(bytes: ByteArray, expected: ByteArray?): Saved {
        val current = try {
            read()
        } catch (e: Exception) {
            return Saved.Failed(e.message ?: e.javaClass.simpleName, restored = false)
        }
        if (expected != null && !sha256(current).contentEquals(expected)) return Saved.Changed

        try {
            keepPrevious(current)
        } catch (e: Exception) {
            // Without the copy from before, the save does not go ahead: a failure halfway would
            // then have nothing to put back.
            return Saved.Failed("could not keep the copy from before: ${e.message}", restored = false)
        }

        val newHash = sha256(bytes)
        Pending.begin(context, uri, newHash)
        val problem = try {
            writeWhole(bytes)
            if (sha256(read()).contentEquals(newHash)) null else "it read back different"
        } catch (e: Exception) {
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
        if (restored) Pending.end(context)
        return Saved.Failed(problem, restored)
    }

    private fun writeWhole(bytes: ByteArray) {
        // "rwt" truncates; some providers only know "wt".
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "rwt") }.getOrNull()
            ?: context.contentResolver.openFileDescriptor(uri, "wt")
            ?: throw IOException("nothing answered for the file")
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
        private const val PREVIOUS = "previous.kdbx"
        private const val PREVIOUS_FROM = "previous.from"

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun previousDir(context: Context) = File(context.noBackupFilesDir, "previous")

        /** The copy kept from before the last save of the file at [uri], if there is one. */
        fun previous(context: Context, uri: Uri): ByteArray? {
            val dir = previousDir(context)
            val from = runCatching { File(dir, PREVIOUS_FROM).readText() }.getOrNull()
            val file = File(dir, PREVIOUS)
            return if (from == uri.toString() && file.isFile) file.readBytes() else null
        }

        /** Forget the copy from before, when the vault it belonged to is closed. */
        fun forgetPrevious(context: Context) {
            previousDir(context).deleteRecursively()
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
