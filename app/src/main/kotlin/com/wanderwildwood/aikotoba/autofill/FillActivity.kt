package com.wanderwildwood.aikotoba.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.keemobile.kotpass.database.modifiers.modifyEntry
import app.keemobile.kotpass.models.EntryValue
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.aikotoba.MainActivity
import com.wanderwildwood.aikotoba.Prefs
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.ui.BarButton
import com.wanderwildwood.aikotoba.ui.Frame
import com.wanderwildwood.aikotoba.ui.Icons
import com.wanderwildwood.aikotoba.ui.PlainRow
import com.wanderwildwood.aikotoba.ui.Say
import com.wanderwildwood.aikotoba.ui.StartScreen
import com.wanderwildwood.aikotoba.ui.UnlockScreen
import com.wanderwildwood.aikotoba.ui.monochrome
import com.wanderwildwood.aikotoba.vault.Item
import com.wanderwildwood.aikotoba.vault.Session
import com.wanderwildwood.aikotoba.vault.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Opened from another app's sign-in screen: unlock if need be, choose the entry, and its user
 * name and password go into that screen. The entries that belong to that app or site are
 * listed first; the rest are a search away.
 */
class FillActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivity.secure(this)
        @Suppress("DEPRECATION")
        val structure = intent.getParcelableExtra<AssistStructure>(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        if (structure == null) {
            finish()
            return
        }
        val fields = Fields.from(structure)
        val site = fields.site(this)
        val appName = runCatching {
            @Suppress("DEPRECATION") // the flags-object overload is Android 13; the Kompakt is 12
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(fields.packageName, 0)).toString()
        }.getOrDefault(fields.packageName)
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                val open by Session.state.collectAsState()
                val prefs = remember { Prefs(this) }
                var target by remember { mutableStateOf(prefs.vault) }
                val vault = open
                when {
                    vault != null -> Pick(vault, fields, site, site ?: appName, prefs) { item -> fill(item, fields, site, prefs) }
                    target == null -> StartScreen(onOpened = { prefs.vault = it; target = it }, onNew = {}, onAbout = {})
                    else -> UnlockScreen(uri = target!!, visiting = false, onOther = { prefs.vault = it; target = it }, onNew = {}, onAbout = {})
                }
            }
        }
    }

    private fun fill(item: Item, fields: Fields, site: String?, prefs: Prefs) {
        val open = Session.open ?: return
        val dataset = FillService.dataset(this, item, Vault.password(open.db, item.uuid), fields)
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        // An app (not a web page) the entry did not name yet: name it, so it is offered there next time.
        if (prefs.rememberForApp && site == null && !Match.matches(item, fields.packageName, null) && open.writable) {
            val context = applicationContext
            val claim = Match.claim(fields.packageName)
            background.launch {
                Session.save(context) { db ->
                    val entry = Vault.find(db, item.uuid) ?: return@save db
                    // KeePassXC reads KP2A_URL fields as further addresses of an entry.
                    val key = (0..99).map { if (it == 0) "KP2A_URL" else "KP2A_URL_$it" }.first { it !in entry.fields }
                    db.modifyEntry(item.uuid) { copy(fields = this.fields + (key to EntryValue.Plain(claim))) }
                }
            }
        }
        finish()
    }

    companion object {
        /** Saving after the activity has gone: the change is small and the vault is still open. */
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

@Composable
private fun Pick(open: Session.Open, fields: Fields, site: String?, asking: String, prefs: Prefs, onPick: (Item) -> Unit) {
    var query by remember { mutableStateOf("") }
    var rememberApp by remember { mutableStateOf(prefs.rememberForApp) }
    val q = query.trim()
    val (matching, rest) = open.items.partition { Match.matches(it, fields.packageName, site) }
    val shown = (matching + rest).filter { q.isEmpty() || it.title.contains(q, true) || it.username.contains(q, true) || it.url.contains(q, true) }
    Frame(title = stringResource(R.string.fill_title, asking)) {
        TextFieldMMD(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { TextMMD(text = stringResource(R.string.home_search), style = MaterialTheme.typography.labelSmall) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
            trailingIcon = { if (query.isNotEmpty()) BarButton(Icons.Close, stringResource(R.string.cd_clear)) { query = "" } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumnMMD(Modifier.fillMaxSize()) {
            if (site == null) {
                item {
                    PlainRow(
                        title = stringResource(R.string.fill_remember, asking),
                        trailing = { SwitchMMD(checked = rememberApp, onCheckedChange = null) },
                        onPress = { rememberApp = !rememberApp; prefs.rememberForApp = rememberApp },
                    )
                }
            }
            if (shown.isEmpty()) item { Say(stringResource(R.string.fill_none)) }
            for (item in shown) {
                item(key = item.uuid.toString()) {
                    PlainRow(
                        title = item.label,
                        note = item.username.ifBlank { null },
                        bold = item in matching,
                        noteLines = 1,
                        onPress = { onPick(item) },
                    )
                }
            }
        }
    }
}
