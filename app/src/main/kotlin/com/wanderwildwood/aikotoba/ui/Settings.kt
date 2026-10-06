package com.wanderwildwood.aikotoba.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.autofill.AutofillManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.wanderwildwood.aikotoba.Locker
import com.wanderwildwood.aikotoba.Prefs
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.vault.Session
import com.wanderwildwood.aikotoba.vault.VaultFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    open: Session.Open,
    prefs: Prefs,
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onBringIn: () -> Unit,
    onGenerate: () -> Unit,
    onOther: (Uri) -> Unit,
    onClose: () -> Unit,
    actions: Actions,
) {
    val context = LocalContext.current
    var lockMinutes by remember { mutableIntStateOf(prefs.lockMinutes) }
    var rememberApp by remember { mutableStateOf(prefs.rememberForApp) }
    var fillOn by remember { mutableStateOf(false) }
    var hasPrevious by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current
    // Read again on every return, since the autofill choice is made in the system's settings.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            fillOn = context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
            hasPrevious = withContext(Dispatchers.IO) { VaultFile.previous(context, open.uri) != null }
        }
    }
    val pickOther = rememberVaultPicker(onOther)
    val copy = rememberFileMaker { uri -> actions.saveCopy(uri) }

    Frame(title = stringResource(R.string.settings_title), onBack = onBack) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            item { Heading(stringResource(R.string.settings_vault)) }
            item {
                PlainRow(
                    title = stringResource(R.string.settings_file),
                    note = if (open.writable) open.name else stringResource(R.string.settings_file_read_only, open.name),
                    noteLines = 3,
                    onPress = pickOther,
                )
            }
            item {
                PlainRow(
                    title = stringResource(R.string.settings_copy),
                    note = stringResource(R.string.settings_copy_note),
                    noteLines = 4,
                    onPress = { copy(open.name.ifBlank { "Passwords.kdbx" }) },
                )
            }
            if (hasPrevious) {
                item {
                    val (armed, press) = rememberArmed("previous") { actions.putBackPrevious() }
                    PlainRow(
                        title = stringResource(if (armed) R.string.settings_previous_armed else R.string.settings_previous),
                        note = if (armed) null else stringResource(R.string.settings_previous_note),
                        bold = armed,
                        noteLines = 3,
                        onPress = press,
                    )
                }
            }
            if (!Session.hasKeyFile) {
                item { PlainRow(stringResource(R.string.settings_password), onPress = onChangePassword) }
            } else {
                item { PlainRow(stringResource(R.string.settings_password), stringResource(R.string.settings_password_key_file), noteLines = 3, onPress = null) }
            }

            item { Heading(stringResource(R.string.settings_locking)) }
            item {
                PlainRow(
                    title = stringResource(R.string.settings_lock_after),
                    note = if (lockMinutes == 0) stringResource(R.string.settings_lock_leaving)
                    else pluralStringResource(R.plurals.settings_lock_minutes, lockMinutes, lockMinutes.toString()),
                    onPress = {
                        val all = Prefs.LOCK_CHOICES
                        lockMinutes = all[(all.indexOf(lockMinutes) + 1) % all.size]
                        prefs.lockMinutes = lockMinutes
                    },
                )
            }
            item { Say(stringResource(R.string.settings_lock_note)) }

            item { Heading(stringResource(R.string.settings_other_apps)) }
            item {
                PlainRow(
                    title = stringResource(R.string.settings_fill),
                    note = stringResource(if (fillOn) R.string.settings_fill_on else R.string.settings_fill_off),
                    noteLines = 3,
                    onPress = {
                        Locker.errand()
                        launch(context) {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                                    .setData(Uri.parse("package:" + context.packageName)),
                            )
                        }
                    },
                )
            }
            item {
                PlainRow(
                    title = stringResource(R.string.settings_remember),
                    note = stringResource(R.string.settings_remember_note),
                    noteLines = 3,
                    trailing = { SwitchMMD(checked = rememberApp, onCheckedChange = null) },
                    onPress = { rememberApp = !rememberApp; prefs.rememberForApp = rememberApp },
                )
            }
            if (Build.MANUFACTURER.equals("Mudita", ignoreCase = true)) {
                item {
                    PlainRow(
                        title = stringResource(R.string.settings_duraspeed),
                        note = stringResource(R.string.settings_duraspeed_note),
                        noteLines = 4,
                        onPress = {
                            launch(context) {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:com.mediatek.duraspeed")),
                                )
                            }
                        },
                    )
                }
            }
            item { PlainRow(stringResource(R.string.gen_open), onPress = onGenerate) }
            item { PlainRow(stringResource(R.string.settings_bring_in), stringResource(R.string.settings_bring_in_note), noteLines = 3, onPress = onBringIn) }

            item { Spacer(Modifier.height(16.dp)) }
            item {
                PlainRow(stringResource(R.string.settings_close), stringResource(R.string.settings_close_note), noteLines = 3, onPress = onClose)
            }
        }
    }
}

@Composable
fun ChangePasswordScreen(onBack: () -> Unit, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    fun go() {
        problem = when {
            first.length < 8 -> context.getString(R.string.create_too_short)
            first != second -> context.getString(R.string.create_mismatch)
            else -> null
        }
        if (problem == null) onChange(first)
    }
    Frame(title = stringResource(R.string.settings_password), onBack = onBack, backIcon = Icons.Close) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            item { Say(stringResource(R.string.password_what), Modifier.padding(top = 8.dp)) }
            item {
                Column(Modifier.padding(16.dp)) {
                    PasswordField(first, { first = it; problem = null }, stringResource(R.string.create_password), ImeAction.Next) {}
                    Spacer(Modifier.height(12.dp))
                    PasswordField(second, { second = it; problem = null }, stringResource(R.string.create_again), ImeAction.Done) { go() }
                    problem?.let { Say(it, bold = true) }
                    Spacer(Modifier.height(12.dp))
                    FootButton(stringResource(R.string.password_go)) { go() }
                }
            }
        }
    }
}

/**
 * The file was saved by someone else since it was opened. Nothing has been written; the person
 * chooses which version wins, or keeps both.
 */
@Composable
fun ConflictDialog(actions: Actions, onReload: () -> Unit, onReplace: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val copy = rememberFileMaker { uri ->
        actions.saveCopy(uri)
        onCopy()
    }
    val name = stringResource(R.string.conflict_copy_name)
    EInkDialog(onDismiss = onDismiss) {
        Say(stringResource(R.string.conflict_what), Modifier.padding(bottom = 8.dp))
        FootButton(stringResource(R.string.conflict_reload), onClick = onReload)
        Spacer(Modifier.height(10.dp))
        FootButton(stringResource(R.string.conflict_copy)) { copy(name) }
        Spacer(Modifier.height(10.dp))
        val (armed, press) = rememberArmed("replace", onReplace)
        FootButton(stringResource(if (armed) R.string.conflict_replace_armed else R.string.conflict_replace), onClick = press)
    }
}
