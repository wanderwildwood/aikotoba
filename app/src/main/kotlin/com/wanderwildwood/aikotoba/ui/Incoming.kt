package com.wanderwildwood.aikotoba.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wanderwildwood.aikotoba.importer.Import
import com.wanderwildwood.aikotoba.importer.Recognised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What another app handed over: a vault file to open, or codes (a link, a QR picture, an export). */
object Incoming {

    sealed class What {
        /**
         * A vault file opened from another app. It is opened for now only and never becomes the
         * remembered vault this way: that takes Open a different vault, the app's own choice.
         */
        data class VaultFile(val uri: Uri) : What()
        data class Codes(val recognised: Recognised) : What()
        object Nothing : What()
    }

    private const val SHARED_FILE_CAP = 8 * 1024 * 1024

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Whether [scheme] is a content address's, the only kind of vault file taken from another app. */
    fun isContent(scheme: String?): Boolean = scheme.equals("content", ignoreCase = true)

    /**
     * Whether a vault file handed over by another app needs a yes before it is opened: when a
     * different vault is open or waiting to be unlocked. [current] is that vault, if any.
     */
    fun <T> asksFirst(current: T?, handed: T): Boolean = current != null && current != handed

    /** Files and pictures are read off the main thread; called from a coroutine. */
    suspend fun read(context: Context, intent: Intent): What = when (intent.action) {
        Intent.ACTION_VIEW -> {
            val uri = intent.data
            if (uri == null) What.Nothing
            else if (uri.scheme.equals("otpauth", true) || uri.scheme.equals("otpauth-migration", true)) {
                What.Codes(Import.recognise(uri.toString()))
            } else if (!isContent(uri.scheme)) {
                // Only a file handed over through a content address with its own grant; a bare
                // file:// path is not opened.
                What.Nothing
            } else {
                if ((intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) runCatching {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                }
                What.VaultFile(uri)
            }
        }
        Intent.ACTION_SEND -> {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            @Suppress("DEPRECATION")
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            when {
                text != null -> What.Codes(Import.recognise(text))
                stream != null && intent.type.orEmpty().startsWith("image/") -> What.Codes(readPicture(context, stream))
                // Off the main thread, and no bigger than an export of codes can be.
                stream != null -> What.Codes(
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = context.contentResolver.openInputStream(stream)!!.use { readAtMost(it, SHARED_FILE_CAP + 1) }
                            if (bytes.size > SHARED_FILE_CAP) Recognised.Nothing
                            else Import.recognise(bytes.toString(Charsets.UTF_8))
                        }.getOrDefault(Recognised.Nothing)
                    },
                )
                else -> What.Nothing
            }
        }
        else -> What.Nothing
    }
}
