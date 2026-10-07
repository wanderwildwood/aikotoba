package com.wanderwildwood.aikotoba.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.caravanfire.calmqr.QrScanner
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.ok1cdj.kauth.core.Base32
import com.ok1cdj.kauth.core.OtpAccount
import com.ok1cdj.kauth.core.OtpAlgorithm
import com.wanderwildwood.aikotoba.Locker
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.importer.Found
import com.wanderwildwood.aikotoba.importer.Import
import com.wanderwildwood.aikotoba.importer.Recognised
import com.wanderwildwood.aikotoba.otp.OtpSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The ways a code comes in: its QR code scanned, a picture already on the phone, a pasted link,
 * the secret typed by hand, or a file from another authenticator.
 *
 * The scanner is this app's own, reading the camera's live picture, so it needs no camera app;
 * the camera permission is asked for the first time it is used, and nothing it sees is kept.
 */
@Composable
fun AddCodeScreen(
    attaching: Boolean,
    defaultIssuer: String,
    defaultAccount: String,
    onBack: () -> Unit,
    onFound: (Recognised) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    fun picture(uri: Uri) {
        reading = true
        scope.launch {
            val r = readPicture(context, uri)
            reading = false
            if (r == Recognised.Nothing) Notice.say(context.getString(R.string.add_code_no_qr)) else onFound(r)
        }
    }

    val allowCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else Notice.say(context.getString(R.string.scan_no_permission))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) picture(uri) }
    val file = rememberOnePicker { uri ->
        reading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { Import.recognise(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }) }
                    .getOrDefault(Recognised.Nothing)
            }
            reading = false
            onFound(r)
        }
    }

    if (scanning) {
        ScanScreen(onBack = { scanning = false }, onFound = { r -> scanning = false; onFound(r) })
        return
    }

    if (typing) {
        TypeSecret(defaultIssuer, defaultAccount, onBack = { typing = false }) { spec -> onFound(Recognised.Codes(Found(listOf(spec)))) }
        return
    }

    Frame(title = stringResource(if (attaching) R.string.add_code_title else R.string.add_codes_title), onBack = onBack) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            if (reading) item { Say(stringResource(R.string.add_code_reading), bold = true) }
            item {
                PlainRow(stringResource(R.string.add_code_camera), stringResource(R.string.add_code_camera_note)) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        scanning = true
                    } else {
                        Locker.errand()
                        launch(context) { allowCamera.launch(Manifest.permission.CAMERA) }
                    }
                }
            }
            item {
                PlainRow(stringResource(R.string.add_code_picture), stringResource(R.string.add_code_picture_note)) {
                    Locker.errand()
                    launch(context) { gallery.launch("image/*") }
                }
            }
            item { PlainRow(stringResource(R.string.add_code_type), onPress = { typing = true }) }
            if (!attaching) {
                item { PlainRow(stringResource(R.string.add_code_file), stringResource(R.string.add_code_file_note), noteLines = 3, onPress = file) }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    TextMMD(text = stringResource(R.string.add_code_paste), style = MaterialTheme.typography.labelSmall)
                    TextFieldMMD(
                        value = link,
                        onValueChange = { link = it },
                        // One line, so the keyboard's Done key reads the link rather than starting another line.
                        singleLine = true,
                        placeholder = { TextMMD(text = "otpauth://…", style = MaterialTheme.typography.labelSmall) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank()) onFound(Import.recognise(link)) }),
                        modifier = Modifier.fillMaxWidth().textActions(),
                    )
                    Spacer(Modifier.height(8.dp))
                    FootButton(stringResource(R.string.add_code_use_link), enabled = link.isNotBlank()) { onFound(Import.recognise(link)) }
                }
            }
        }
    }
}

/**
 * The scanner: the camera's live picture, read as it comes. A sign-in code goes straight on to
 * be added; any other code is said not to be one, and the scanner keeps looking.
 */
@Composable
private fun ScanScreen(onBack: () -> Unit, onFound: (Recognised) -> Unit) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onBack)
    Frame(title = stringResource(R.string.scan_title), onBack = onBack) {
        Say(stringResource(R.string.scan_hint))
        QrScanner(
            starting = stringResource(R.string.scan_starting),
            onRead = { text ->
                val t = text.trim()
                val ours = t.startsWith("otpauth://", ignoreCase = true) || t.startsWith("otpauth-migration://", ignoreCase = true)
                val r = if (ours) Import.recognise(t) else Recognised.Nothing
                if (r is Recognised.Codes) {
                    onFound(r)
                    true
                } else {
                    Notice.say(context.getString(R.string.scan_not_two_step))
                    false
                }
            },
            onUnavailable = {
                Notice.say(context.getString(R.string.scan_unavailable))
                onBack()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** A code from its secret, typed: what a site shows under "can't scan the code?". */
@Composable
private fun TypeSecret(issuer: String, account: String, onBack: () -> Unit, onDone: (OtpSpec) -> Unit) {
    var name by remember { mutableStateOf(issuer) }
    var user by remember { mutableStateOf(account) }
    var secret by remember { mutableStateOf("") }
    var digits by remember { mutableStateOf(6) }
    var period by remember { mutableStateOf(30) }
    var algorithm by remember { mutableStateOf(OtpAlgorithm.SHA1) }
    val clean = secret.replace(" ", "").replace("-", "").uppercase()
    val valid = clean.isNotEmpty() && Base32.isValid(clean)
    androidx.activity.compose.BackHandler(onBack = onBack)

    Frame(
        title = stringResource(R.string.add_code_type),
        onBack = onBack,
        actions = {
            BarWord(stringResource(R.string.add_code_done), ready = valid) {
                onDone(OtpSpec(OtpAccount(issuer = name.trim(), name = user.trim(), secret = clean, algorithm = algorithm, digits = digits, period = period)))
            }
        },
    ) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            item { Line(stringResource(R.string.type_issuer), name) { name = it } }
            item { Line(stringResource(R.string.type_account), user) { user = it } }
            item { Line(stringResource(R.string.type_secret), secret, KeyboardCapitalization.Characters) { secret = it } }
            if (secret.isNotBlank() && !valid) item { Say(stringResource(R.string.type_secret_bad), bold = true) }
            item {
                PlainRow(stringResource(R.string.type_digits), digits.toString()) { digits = if (digits == 6) 8 else 6 }
            }
            item {
                PlainRow(stringResource(R.string.type_period), stringResource(R.string.type_seconds, period.toString())) {
                    period = when (period) { 30 -> 60; 60 -> 15; else -> 30 }
                }
            }
            item {
                PlainRow(stringResource(R.string.type_algorithm), algorithm.name) {
                    algorithm = OtpAlgorithm.entries[(algorithm.ordinal + 1) % OtpAlgorithm.entries.size]
                }
            }
            item { Say(stringResource(R.string.type_note)) }
        }
    }
}

@Composable
private fun Line(label: String, value: String, caps: KeyboardCapitalization = KeyboardCapitalization.Sentences, onValue: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
        TextFieldMMD(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = caps, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
