package com.wanderwildwood.aikotoba.ui

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.aikotoba.Locker
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.vault.Fingerprint
import com.wanderwildwood.aikotoba.vault.Session
import com.wanderwildwood.aikotoba.vault.VaultFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nothing chosen yet: open a vault file, or make one. */
@Composable
fun StartScreen(onOpened: (Uri) -> Unit, onNew: (Uri) -> Unit, onAbout: () -> Unit, onGenerate: (() -> Unit)? = null) {
    val pick = rememberVaultPicker(onOpened)
    val make = rememberFileMaker(onNew)
    val newName = stringResource(R.string.new_vault_file_name)
    Frame(
        title = stringResource(R.string.app_name),
        actions = { BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout) },
    ) {
        LazyColumnMMD(Modifier.fillMaxWidth()) {
            item { Say(stringResource(R.string.start_what), Modifier.padding(top = 8.dp)) }
            item { Say(stringResource(R.string.start_file)) }
            item {
                Column(Modifier.padding(16.dp)) {
                    FootButton(stringResource(R.string.start_open), onClick = pick)
                    Spacer(Modifier.height(12.dp))
                    FootButton(stringResource(R.string.start_new)) { make(newName) }
                }
            }
            if (onGenerate != null) item { PlainRow(stringResource(R.string.gen_open), onPress = onGenerate) }
        }
    }
}

/**
 * The master password, and the keyboard's own Done key opens the vault: there is no separate
 * press to make after typing it. With fingerprint unlock on for this vault, the phone's
 * fingerprint sheet comes up first and a button brings it back; the password field stays.
 */
@Composable
fun UnlockScreen(
    uri: Uri,
    visiting: Boolean,
    onOther: (Uri) -> Unit,
    onNew: (Uri) -> Unit,
    onAbout: () -> Unit,
    onUnlocked: () -> Unit = {},
    onGenerate: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var password by remember(uri) { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var keyFile by remember(uri) { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember(uri) { mutableStateOf<String?>(null) }
    val name = remember(uri) { VaultFile(context, uri).name() }
    var interrupted by remember(uri) { mutableStateOf(false) }
    var fingerprint by remember(uri) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val fingerprintTitle = stringResource(R.string.fingerprint_open_title)
    val fingerprintNegative = stringResource(R.string.fingerprint_use_password)

    fun opened(r: Session.Opened, byFingerprint: Boolean) {
        when (r) {
            Session.Opened.Done -> {
                password = ""
                onUnlocked()
            }
            Session.Opened.WrongPassword -> if (byFingerprint) {
                // The file's password was changed elsewhere (a computer): what was kept no longer opens it.
                Fingerprint.forget(context)
                fingerprint = false
                problem = context.getString(R.string.fingerprint_password_changed)
            } else {
                problem = context.getString(R.string.unlock_wrong)
            }
            is Session.Opened.NotKeePass -> problem = context.getString(R.string.unlock_not_keepass, r.reason)
            is Session.Opened.Unreadable -> problem = context.getString(R.string.unlock_unreadable, r.reason)
        }
    }

    fun unlock() {
        if (busy || password.isEmpty() && keyFile == null) return
        busy = true
        problem = null
        keyboard?.hide()
        scope.launch {
            val r = withContext(Dispatchers.IO) { Session.unlock(context, uri, password, keyFile?.second) }
            busy = false
            opened(r, byFingerprint = false)
        }
    }

    /** The phone's fingerprint sheet; what it unlocks is the master password, kept encrypted. */
    fun unlockByFingerprint() {
        if (busy) return
        val cipher = try {
            Fingerprint.cipherToOpen(context, uri)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // A fingerprint was added or removed on the phone: the key is gone, by design.
            Fingerprint.forget(context)
            fingerprint = false
            problem = context.getString(R.string.fingerprint_changed)
            runCatching { focus.requestFocus() }
            return
        } catch (e: Exception) {
            Fingerprint.forget(context)
            fingerprint = false
            problem = context.getString(R.string.fingerprint_failed, e.message ?: e.javaClass.simpleName)
            runCatching { focus.requestFocus() }
            return
        }
        if (cipher == null) {
            fingerprint = false
            runCatching { focus.requestFocus() }
            return
        }
        problem = null
        keyboard?.hide()
        Fingerprint.prompt(context, fingerprintTitle, name, fingerprintNegative, cipher) { read ->
            when (read) {
                is Fingerprint.Read.Done -> {
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            val secret = runCatching { Fingerprint.open(context, read.cipher) }.getOrNull()
                                ?: return@withContext null
                            Session.unlock(context, uri, secret.password, secret.keyFile)
                        }
                        busy = false
                        if (r == null) {
                            Fingerprint.forget(context)
                            fingerprint = false
                            problem = context.getString(R.string.fingerprint_changed)
                            runCatching { focus.requestFocus() }
                        } else {
                            opened(r, byFingerprint = true)
                        }
                    }
                }
                Fingerprint.Read.Cancelled -> runCatching { focus.requestFocus() }
                is Fingerprint.Read.Failed -> {
                    problem = context.getString(R.string.fingerprint_failed, read.message)
                    runCatching { focus.requestFocus() }
                }
            }
        }
    }

    LaunchedEffect(uri) {
        val (reachable, wasInterrupted, enrolled) = withContext(Dispatchers.IO) {
            val current = runCatching { VaultFile(context, uri).read() }.getOrNull()
            Triple(
                current != null,
                VaultFile.Pending.interrupted(context, uri, current) && VaultFile.previous(context, uri) != null,
                current != null && Fingerprint.enrolledFor(context, uri) && Fingerprint.available(context) is Fingerprint.Available.Yes,
            )
        }
        interrupted = wasInterrupted
        fingerprint = enrolled
        // Said here, before any typing: a file whose app is gone fails the same way on every try.
        if (!reachable) problem = context.getString(R.string.unlock_unreachable)
        if (enrolled) unlockByFingerprint() else runCatching { focus.requestFocus() }
    }

    val pickOther = rememberVaultPicker(onOther)
    val make = rememberFileMaker(onNew)
    val newName = stringResource(R.string.new_vault_file_name)
    val pickKey = rememberOnePicker { u ->
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(u)!!.use { it.readBytes() } }.getOrNull() }
            if (bytes == null) problem = context.getString(R.string.key_file_unreadable)
            else keyFile = VaultFile(context, u).name() to bytes
        }
    }

    Frame(
        title = stringResource(R.string.app_name),
        actions = { BarButton(Icons.Info, stringResource(R.string.cd_about), onAbout) },
    ) {
        LazyColumnMMD(Modifier.fillMaxWidth()) {
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    TextMMD(text = name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    if (visiting) TextMMD(text = stringResource(R.string.unlock_visiting), style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(12.dp))
                    TextFieldMMD(
                        value = password,
                        onValueChange = { password = it; problem = null },
                        singleLine = true,
                        enabled = !busy,
                        placeholder = { TextMMD(text = stringResource(R.string.unlock_password), style = MaterialTheme.typography.labelSmall) },
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { unlock() }),
                        trailingIcon = {
                            BarButton(
                                if (shown) Icons.VisibilityOff else Icons.Visibility,
                                stringResource(if (shown) R.string.cd_hide else R.string.cd_show),
                            ) { shown = !shown }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TextMMD(
                            text = keyFile?.let { stringResource(R.string.key_file_is, it.first) } ?: stringResource(R.string.key_file_add),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f).clickable(enabled = !busy) { if (keyFile == null) pickKey() else keyFile = null }.padding(vertical = 10.dp),
                        )
                    }
                    when {
                        busy -> Say(stringResource(R.string.unlock_opening), Modifier.padding(top = 4.dp))
                        problem != null -> Say(problem!!, Modifier.padding(top = 4.dp), bold = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    FootButton(stringResource(R.string.unlock), enabled = !busy) { unlock() }
                    if (fingerprint) {
                        Spacer(Modifier.height(10.dp))
                        FootButton(stringResource(R.string.unlock_fingerprint), enabled = !busy) { unlockByFingerprint() }
                    }
                }
            }
            if (interrupted) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Say(stringResource(R.string.interrupted), bold = true)
                        FootButton(stringResource(R.string.interrupted_put_back)) {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val previous = VaultFile.previous(context, uri)
                                    previous != null && VaultFile(context, uri).write(previous, expected = null) is VaultFile.Saved.Done
                                }
                                interrupted = false
                                Notice.say(context.getString(if (ok) R.string.previous_put_back else R.string.previous_failed))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FootButton(stringResource(R.string.interrupted_leave)) {
                            VaultFile.Pending.end(context)
                            interrupted = false
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { PlainRow(stringResource(R.string.unlock_other), onPress = pickOther) }
            item { PlainRow(stringResource(R.string.start_new), onPress = { make(newName) }) }
            if (onGenerate != null) item { PlainRow(stringResource(R.string.gen_open), onPress = onGenerate) }
        }
    }
}

/** A new vault's master password, typed twice. */
@Composable
fun CreateScreen(busy: Boolean, onCreate: (String) -> Unit, onCancel: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val again = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.activity.compose.BackHandler(onBack = onCancel)

    fun go() {
        problem = when {
            first.length < 8 -> context.getString(R.string.create_too_short)
            first != second -> context.getString(R.string.create_mismatch)
            else -> null
        }
        if (problem == null && !busy) onCreate(first)
    }

    Frame(title = stringResource(R.string.create_title), onBack = onCancel, backIcon = Icons.Close) {
        LazyColumnMMD(Modifier.fillMaxWidth()) {
            item { Say(stringResource(R.string.create_what), Modifier.padding(top = 8.dp)) }
            item {
                Column(Modifier.padding(16.dp)) {
                    PasswordField(first, { first = it; problem = null }, stringResource(R.string.create_password), ImeAction.Next, Modifier.focusRequester(focus), next = again) {}
                    Spacer(Modifier.height(12.dp))
                    PasswordField(second, { second = it; problem = null }, stringResource(R.string.create_again), ImeAction.Done, Modifier.focusRequester(again)) { go() }
                    problem?.let { Say(it, bold = true) }
                    if (busy) Say(stringResource(R.string.create_making))
                    Spacer(Modifier.height(12.dp))
                    FootButton(stringResource(R.string.create_go), enabled = !busy) { go() }
                }
            }
        }
    }
}

/** A password box with its own show/hide, ending in [onDone] when the keyboard's key says so. */
@Composable
internal fun PasswordField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    ime: ImeAction,
    modifier: Modifier = Modifier,
    next: FocusRequester? = null,
    onDone: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    TextFieldMMD(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        placeholder = { TextMMD(text = placeholder, style = MaterialTheme.typography.labelSmall) },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ime, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = { next?.requestFocus() }),
        trailingIcon = {
            BarButton(if (shown) Icons.VisibilityOff else Icons.Visibility, stringResource(if (shown) R.string.cd_hide else R.string.cd_show)) { shown = !shown }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Fingerprint unlock turned on for [uri], with the vault open: the master password (and key
 * file) go behind a fresh key-store key, after one reading at the phone's fingerprint sheet.
 * [onDone] hears whether it is on now. Anything short of a full success leaves it off.
 */
internal fun enrolFingerprint(context: Context, uri: Uri, title: String, subtitle: String?, onDone: (Boolean) -> Unit) {
    val secret = Session.secret()
    if (secret == null) {
        Notice.say(context.getString(R.string.fingerprint_cannot, "the vault is locked"))
        onDone(false)
        return
    }
    val cipher = try {
        Fingerprint.cipherToEnrol(uri)
    } catch (e: Exception) {
        Fingerprint.forget(context)
        Notice.say(context.getString(R.string.fingerprint_cannot, e.message ?: e.javaClass.simpleName))
        onDone(false)
        return
    }
    // The sheet is the phone's, not this app's; it must not count as leaving.
    Locker.errand()
    Fingerprint.prompt(context, title, subtitle, context.getString(R.string.fingerprint_not_now), cipher) { read ->
        when (read) {
            is Fingerprint.Read.Done -> {
                val failed = runCatching { Fingerprint.enrol(context, uri, read.cipher, secret) }.exceptionOrNull()
                if (failed == null) {
                    Notice.say(context.getString(R.string.fingerprint_on))
                    onDone(true)
                } else {
                    Fingerprint.forget(context)
                    Notice.say(context.getString(R.string.fingerprint_cannot, failed.message ?: failed.javaClass.simpleName))
                    onDone(false)
                }
            }
            Fingerprint.Read.Cancelled -> {
                Fingerprint.forget(context)
                onDone(false)
            }
            is Fingerprint.Read.Failed -> {
                Fingerprint.forget(context)
                Notice.say(context.getString(R.string.fingerprint_cannot, read.message))
                onDone(false)
            }
        }
    }
}
