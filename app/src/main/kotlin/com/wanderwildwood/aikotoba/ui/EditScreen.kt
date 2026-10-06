package com.wanderwildwood.aikotoba.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.otp.OtpField

/** A new entry or a change to one. Nothing is written until Save. */
@Composable
fun EditScreen(route: Route.Edit, onClose: () -> Unit, onSave: (com.wanderwildwood.aikotoba.vault.Draft) -> Unit, onAddCode: () -> Unit, onGenerate: () -> Unit) {
    val context = LocalContext.current
    var draft by route.draft
    val start = remember(route) { route.draft.value }
    val dirty = draft != start
    var shown by remember { mutableStateOf(route.uuid == null) }
    val (leaving, leave) = rememberArmed(dirty) { onClose() }
    val close = {
        if (!dirty) onClose() else {
            if (!leaving) Notice.say(context.getString(R.string.edit_discard_armed))
            leave()
        }
    }
    BackHandler(onBack = close)

    Frame(
        title = stringResource(if (route.uuid == null) R.string.edit_new_title else R.string.edit_title),
        onBack = close,
        backIcon = Icons.Close,
        actions = {
            BarWord(stringResource(R.string.save), ready = draft.title.isNotBlank() || draft.username.isNotBlank() || draft.otp.isNotBlank()) {
                onSave(draft)
            }
        },
    ) {
        LazyColumnMMD(Modifier.fillMaxSize()) {
            item { Field(stringResource(R.string.label_title), draft.title, { draft = draft.copy(title = it) }, KeyboardCapitalization.Sentences) }
            item {
                Field(stringResource(R.string.label_username), draft.username, { draft = draft.copy(username = it) }, KeyboardCapitalization.None, KeyboardType.Email)
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    TextMMD(text = stringResource(R.string.label_password), style = MaterialTheme.typography.labelSmall)
                    TextFieldMMD(
                        value = draft.password,
                        onValueChange = { draft = draft.copy(password = it) },
                        singleLine = true,
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        trailingIcon = {
                            BarButton(if (shown) Icons.VisibilityOff else Icons.Visibility, stringResource(if (shown) R.string.cd_hide else R.string.cd_show)) { shown = !shown }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        FootButton(stringResource(R.string.edit_generate), Modifier.weight(1f), onClick = onGenerate)
                    }
                }
            }
            item { Field(stringResource(R.string.label_url), draft.url, { draft = draft.copy(url = it) }, KeyboardCapitalization.None, KeyboardType.Uri) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    TextMMD(text = stringResource(R.string.label_code), style = MaterialTheme.typography.labelSmall)
                    val spec = draft.otp.takeIf { it.isNotBlank() }?.let(OtpField::parse)
                    TextMMD(
                        text = when {
                            draft.otp.isBlank() -> stringResource(R.string.edit_no_code)
                            spec == null -> stringResource(R.string.code_unreadable)
                            spec.steam -> stringResource(R.string.edit_code_steam)
                            spec.isCounter -> stringResource(R.string.edit_code_counter, spec.account.digits.toString())
                            else -> stringResource(R.string.edit_code_is, spec.account.digits.toString(), spec.period.toString(), spec.account.algorithm.name)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        FootButton(stringResource(if (draft.otp.isBlank()) R.string.edit_add_code else R.string.edit_replace_code), Modifier.weight(1f), onClick = onAddCode)
                        if (draft.otp.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            val (armed, press) = rememberArmed(draft.otp) { draft = draft.copy(otp = "") }
                            FootButton(stringResource(if (armed) R.string.edit_remove_code_armed else R.string.edit_remove_code), Modifier.weight(1f), onClick = press)
                        }
                    }
                }
                DottedRule()
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    TextMMD(text = stringResource(R.string.label_notes), style = MaterialTheme.typography.labelSmall)
                    TextFieldMMD(
                        value = draft.notes,
                        onValueChange = { draft = draft.copy(notes = it) },
                        minLines = 3,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().textActions(),
                    )
                }
            }
            item { Spacer(Modifier.height(240.dp)) }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    capitalization: KeyboardCapitalization,
    type: KeyboardType = KeyboardType.Text,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
        TextFieldMMD(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = capitalization, keyboardType = type, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().textActions(),
        )
    }
}
