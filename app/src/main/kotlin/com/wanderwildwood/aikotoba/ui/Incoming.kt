package com.wanderwildwood.aikotoba.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wanderwildwood.aikotoba.importer.Import
import com.wanderwildwood.aikotoba.importer.Recognised

/** What another app handed over: a vault file to open, or codes (a link, a QR picture, an export). */
object Incoming {

    sealed class What {
        /** [remembered]: the app may open it again after a restart; otherwise it is for now only. */
        data class VaultFile(val uri: Uri, val remembered: Boolean) : What()
        data class Codes(val recognised: Recognised) : What()
        object Nothing : What()
    }

    /** Blocking only for a picture, which is read for its QR code; called from a coroutine. */
    suspend fun read(context: Context, intent: Intent): What = when (intent.action) {
        Intent.ACTION_VIEW -> {
            val uri = intent.data
            if (uri == null) What.Nothing
            else if (uri.scheme.equals("otpauth", true) || uri.scheme.equals("otpauth-migration", true)) {
                What.Codes(Import.recognise(uri.toString()))
            } else {
                val kept = (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0 && runCatching {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                }.isSuccess
                What.VaultFile(uri, kept)
            }
        }
        Intent.ACTION_SEND -> {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            @Suppress("DEPRECATION")
            val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            when {
                text != null -> What.Codes(Import.recognise(text))
                stream != null && intent.type.orEmpty().startsWith("image/") -> What.Codes(readPicture(context, stream))
                stream != null -> What.Codes(
                    runCatching {
                        Import.recognise(context.contentResolver.openInputStream(stream)!!.use { it.readBytes().toString(Charsets.UTF_8) })
                    }.getOrDefault(Recognised.Nothing),
                )
                else -> What.Nothing
            }
        }
        else -> What.Nothing
    }
}
