package com.wanderwildwood.aikotoba.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.wanderwildwood.aikotoba.Locker
import com.wanderwildwood.aikotoba.R

/**
 * The system's file picker for a vault file, kept with the right to read and write it across
 * restarts. Returns the press that opens it.
 */
@Composable
fun rememberVaultPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            keep(context, uri)
            onPicked(uri)
        }
    }
    return {
        Locker.errand()
        launch(context) { launcher.launch(arrayOf("*/*")) }
    }
}

/** The system's "save as", for a new vault or a copy of one. */
@Composable
fun rememberFileMaker(onMade: (Uri) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            keep(context, uri)
            onMade(uri)
        }
    }
    return { name ->
        Locker.errand()
        launch(context) { launcher.launch(name) }
    }
}

/** Any file, read once: a key file, an export from another app. */
@Composable
fun rememberOnePicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) onPicked(uri) }
    return {
        Locker.errand()
        launch(context) { launcher.launch(arrayOf("*/*")) }
    }
}

private fun keep(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }.recoverCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/** Starting another app, and saying so when there is none that can. */
fun launch(context: Context, start: () -> Unit) {
    try {
        start()
    } catch (e: Exception) {
        Notice.say(context.getString(R.string.no_app_for_that))
    }
}
