package com.wanderwildwood.aikotoba.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.keemobile.kotpass.database.KeePassDatabase
import com.wanderwildwood.aikotoba.Prefs
import com.wanderwildwood.aikotoba.R
import com.wanderwildwood.aikotoba.importer.Found
import com.wanderwildwood.aikotoba.importer.Import
import com.wanderwildwood.aikotoba.importer.Qr
import com.wanderwildwood.aikotoba.importer.Recognised
import com.wanderwildwood.aikotoba.otp.OtpField
import com.wanderwildwood.aikotoba.vault.Draft
import com.wanderwildwood.aikotoba.vault.Fingerprint
import com.wanderwildwood.aikotoba.vault.Session
import com.wanderwildwood.aikotoba.vault.Vault
import com.wanderwildwood.aikotoba.vault.VaultFile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the person is, inside an open vault. */
sealed class Route {
    object Home : Route()
    data class Entry(val uuid: UUID) : Route()
    /** A new entry ([uuid] null) or a change to one; [draft] is what has been typed so far. */
    class Edit(val uuid: UUID?, val draft: MutableState<Draft>) : Route()
    /** Adding a code: to the entry being edited ([into]), or as new entries. */
    class AddCode(val into: Edit?) : Route()
    class Review(val found: Found) : Route()
    class Kauth(val blob: String, val into: Edit?) : Route()
    /** The password generator: for the entry being edited ([into]), or on its own. */
    class Generator(val into: Edit?) : Route()
    object Settings : Route()
    object ChangePassword : Route()
}

/**
 * The whole app: a vault to choose, then to unlock, then what is in it.
 *
 * Locking (by hand, by the clock, by the screen going dark) drops the open vault and with it
 * every screen inside it; unlocking starts again at the list.
 */
@Composable
fun App(incoming: Intent?, onIncomingTaken: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val open by Session.state.collectAsState()

    // The file to unlock: the one remembered, or one handed over by another app for now.
    var target by remember { mutableStateOf(prefs.vault) }
    var visiting by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf<Uri?>(null) }
    // Codes shared to the app while it was locked, waiting for the unlock.
    var waiting by remember { mutableStateOf<Found?>(null) }
    var waitingBlob by remember { mutableStateOf<String?>(null) }
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    var about by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }

    LaunchedEffect(open == null) {
        generating = false
        if (open == null) {
            stack.clear()
            stack.add(Route.Home)
            conflict = false
        }
    }

    LaunchedEffect(incoming) {
        val intent = incoming ?: return@LaunchedEffect
        onIncomingTaken()
        when (val what = Incoming.read(context, intent)) {
            is Incoming.What.VaultFile -> {
                if (open?.uri != what.uri) Session.lock()
                target = what.uri
                visiting = !what.remembered
                if (what.remembered) prefs.vault = what.uri
            }
            is Incoming.What.Codes -> when (val r = what.recognised) {
                is Recognised.Codes -> if (open != null) stack.add(Route.Review(r.found)) else waiting = r.found
                is Recognised.KauthBackup -> if (open != null) stack.add(Route.Kauth(r.blob, null)) else waitingBlob = r.blob
                Recognised.AegisEncrypted -> Notice.say(context.getString(R.string.import_aegis_encrypted))
                Recognised.Nothing -> Notice.say(context.getString(R.string.import_nothing))
            }
            Incoming.What.Nothing -> Unit
        }
    }

    LaunchedEffect(open != null) {
        if (open != null) {
            waiting?.let { stack.add(Route.Review(it)); waiting = null }
            waitingBlob?.let { stack.add(Route.Kauth(it, null)); waitingBlob = null }
        }
    }

    val actions = remember { Actions(context, scope, onConflict = { conflict = true }) }
    val onAbout = { about = true }
    val vault = open

    when {
        generating && vault == null -> GeneratorScreen(prefs = prefs, onBack = { generating = false }, onUse = null)
        creating != null -> CreateScreen(
            busy = actions.busy,
            onCreate = { password ->
                actions.create(creating!!, password) { ok ->
                    if (ok) {
                        target = creating
                        visiting = false
                        prefs.vault = creating
                        creating = null
                    }
                }
            },
            onCancel = {
                // The empty file the system made for it goes too, rather than lie about as a
                // "vault" nothing can open.
                creating?.let { uri -> runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                creating = null
            },
        )
        vault == null && target == null -> StartScreen(
            onOpened = { uri -> target = uri; visiting = false; prefs.vault = uri },
            onNew = { uri -> creating = uri },
            onAbout = onAbout,
            onGenerate = { generating = true },
        )
        vault == null -> UnlockScreen(
            uri = target!!,
            visiting = visiting,
            onOther = { uri -> target = uri; visiting = false; prefs.vault = uri },
            onNew = { uri -> creating = uri },
            onAbout = onAbout,
            onGenerate = { generating = true },
        )
        else -> {
            val route = stack.last()
            BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
            val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
            when (route) {
                Route.Home -> HomeScreen(
                    open = vault,
                    prefs = prefs,
                    onEntry = { stack.add(Route.Entry(it)) },
                    onNewEntry = { stack.add(Route.Edit(null, mutableStateOf(Draft("", "", "", "", "", "")))) },
                    onNewCode = { stack.add(Route.AddCode(null)) },
                    onRetrySave = { actions.save { it } },
                    onSaveTo = { uri ->
                        actions.saveAs(uri) {
                            prefs.vault = uri
                            target = uri
                            visiting = false
                        }
                    },
                    onSettings = { stack.add(Route.Settings) },
                    onAbout = onAbout,
                )
                is Route.Entry -> {
                    val item = vault.items.firstOrNull { it.uuid == route.uuid }
                    if (item == null) {
                        LaunchedEffect(route) { back() }
                    } else {
                        EntryScreen(
                            item = item,
                            password = { Vault.password(vault.db, item.uuid) },
                            onBack = back,
                            onEdit = {
                                val draft = Draft(item.title, item.username, Vault.password(vault.db, item.uuid), item.url, item.notes, item.otp?.let(OtpField::link).orEmpty())
                                stack.add(Route.Edit(item.uuid, mutableStateOf(draft)))
                            },
                            onNextCode = { spec -> actions.save { db -> Vault.setOtp(db, item.uuid, OtpField.link(OtpField.next(spec))) } },
                            onDelete = {
                                actions.save(context.getString(R.string.deleted)) { db -> Vault.delete(db, item.uuid) }
                                back()
                            },
                        )
                    }
                }
                is Route.Edit -> EditScreen(
                    route = route,
                    onClose = back,
                    onSave = { draft ->
                        if (route.uuid == null) {
                            actions.save(context.getString(R.string.saved)) { db -> Vault.add(db, draft).first }
                        } else {
                            actions.save(context.getString(R.string.saved)) { db -> Vault.update(db, route.uuid, draft) }
                        }
                        back()
                    },
                    onAddCode = { stack.add(Route.AddCode(route)) },
                    onGenerate = { stack.add(Route.Generator(route)) },
                )
                is Route.Generator -> GeneratorScreen(
                    prefs = prefs,
                    onBack = back,
                    onUse = route.into?.let { into ->
                        { value: String ->
                            into.draft.value = into.draft.value.copy(password = value)
                            back()
                        }
                    },
                )
                is Route.AddCode -> AddCodeScreen(
                    attaching = route.into != null,
                    defaultIssuer = route.into?.draft?.value?.title.orEmpty(),
                    defaultAccount = route.into?.draft?.value?.username.orEmpty(),
                    onBack = back,
                    onFound = { recognised ->
                        when (recognised) {
                            is Recognised.Codes -> {
                                val into = route.into
                                if (into != null) {
                                    val first = recognised.found.codes.firstOrNull()
                                    if (first == null) {
                                        Notice.say(context.getString(R.string.import_nothing))
                                    } else {
                                        into.draft.value = into.draft.value.copy(otp = OtpField.link(first))
                                        if (recognised.found.codes.size > 1) {
                                            Notice.say(context.getString(R.string.add_code_first_of, recognised.found.codes.size.toString()))
                                        }
                                        if (recognised.found.fromGoogle) Notice.say(context.getString(R.string.import_google_short))
                                        back()
                                    }
                                } else {
                                    stack.removeAt(stack.lastIndex)
                                    stack.add(Route.Review(recognised.found))
                                }
                            }
                            is Recognised.KauthBackup -> {
                                stack.removeAt(stack.lastIndex)
                                stack.add(Route.Kauth(recognised.blob, route.into))
                            }
                            Recognised.AegisEncrypted -> Notice.say(context.getString(R.string.import_aegis_encrypted))
                            Recognised.Nothing -> Notice.say(context.getString(R.string.import_nothing))
                        }
                    },
                )
                is Route.Kauth -> KauthScreen(
                    blob = route.blob,
                    onBack = back,
                    onOpened = { found ->
                        stack.removeAt(stack.lastIndex)
                        stack.add(Route.Review(found))
                    },
                )
                is Route.Review -> ReviewScreen(
                    found = route.found,
                    existing = vault.items.mapNotNull { it.otp },
                    onBack = back,
                    onAdd = { chosen ->
                        actions.save(context.resources.getQuantityString(R.plurals.import_added, chosen.size, chosen.size.toString())) { db ->
                            chosen.fold(db) { d, spec ->
                                Vault.add(d, Draft(Import.title(spec), spec.account.name, "", "", "", OtpField.link(spec))).first
                            }
                        }
                        prefs.showCodes = true
                        stack.clear()
                        stack.add(Route.Home)
                    },
                )
                Route.Settings -> SettingsScreen(
                    open = vault,
                    prefs = prefs,
                    onBack = back,
                    onChangePassword = { stack.add(Route.ChangePassword) },
                    onBringIn = { stack.add(Route.AddCode(null)) },
                    onGenerate = { stack.add(Route.Generator(null)) },
                    onOther = { uri ->
                        Session.lock()
                        target = uri
                        visiting = false
                        prefs.vault = uri
                    },
                    onClose = {
                        Session.lock()
                        VaultFile.forgetPrevious(context)
                        Fingerprint.forget(context)
                        prefs.vault = null
                        target = null
                    },
                    actions = actions,
                )
                Route.ChangePassword -> {
                    val keepTitle = stringResource(R.string.fingerprint_keep_title)
                    val keepSubtitle = stringResource(R.string.fingerprint_keep_subtitle)
                    ChangePasswordScreen(
                        onBack = back,
                        onChange = { password ->
                            actions.changePassword(password) { changed ->
                                if (!changed) return@changePassword
                                back()
                                // What fingerprint unlock keeps is the old password now; one reading keeps the new.
                                if (Fingerprint.enrolledFor(context, vault.uri)) {
                                    enrolFingerprint(context, vault.uri, keepTitle, keepSubtitle) { kept ->
                                        if (!kept) Notice.say(context.getString(R.string.fingerprint_off_password_changed))
                                    }
                                }
                            }
                        },
                    )
                }
            }
            if (conflict) {
                ConflictDialog(
                    actions = actions,
                    onReload = { conflict = false; actions.reload() },
                    onReplace = { conflict = false; actions.save(context.getString(R.string.saved), force = true) { it } },
                    onCopy = { conflict = false },
                    onDismiss = { conflict = false },
                )
            }
        }
    }
    if (about) AboutDialog(onDismiss = { about = false })
}

/** The work behind the screens that touches the file: off the main thread, said out loud when it fails. */
class Actions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConflict: () -> Unit,
) {
    var busy by mutableStateOf(false)
        private set

    fun save(done: String? = null, force: Boolean = false, change: (KeePassDatabase) -> KeePassDatabase) {
        Notice.say(context.getString(R.string.saving))
        scope.launch {
            val r = withContext(Dispatchers.IO) { Session.save(context, force, change) }
            when (r) {
                Session.Saved.Done -> Notice.text = done
                Session.Saved.ReadOnly -> Notice.say(context.getString(R.string.not_saved_read_only))
                Session.Saved.Changed -> {
                    Notice.text = null
                    onConflict()
                }
                is Session.Saved.Failed -> Notice.say(
                    context.getString(if (r.restored) R.string.not_saved_failed else R.string.not_saved_failed_unsure, r.reason),
                )
            }
        }
    }

    fun reload() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { Session.reload(context) }
            if (r != Session.Opened.Done) Notice.say(context.getString(R.string.reload_failed))
        }
    }

    fun create(uri: Uri, password: String, then: (Boolean) -> Unit) {
        busy = true
        scope.launch {
            val title = context.getString(R.string.app_name)
            val r = withContext(Dispatchers.IO) { Session.create(context, uri, password, title) }
            busy = false
            if (r is Session.Opened.Unreadable) Notice.say(context.getString(R.string.create_failed, r.reason))
            then(r == Session.Opened.Done)
        }
    }

    fun changePassword(password: String, then: (Boolean) -> Unit) {
        Notice.say(context.getString(R.string.saving))
        scope.launch {
            val r = withContext(Dispatchers.IO) { Session.changePassword(context, password) }
            when (r) {
                Session.Saved.Done -> Notice.say(context.getString(R.string.password_changed))
                Session.Saved.ReadOnly -> Notice.say(context.getString(R.string.not_saved_read_only))
                Session.Saved.Changed -> onConflict()
                is Session.Saved.Failed -> Notice.say(context.getString(R.string.not_saved_failed, r.reason))
            }
            then(r == Session.Saved.Done)
        }
    }

    /** The vault, as it now stands, written to a file the person chose. */
    fun saveCopy(to: Uri) {
        Notice.say(context.getString(R.string.saving))
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = Session.encodeOpen() ?: error("locked")
                    context.contentResolver.openOutputStream(to, "wt")!!.use { it.write(bytes) }
                    val back = context.contentResolver.openInputStream(to)!!.use { it.readBytes() }
                    VaultFile.sha256(back).contentEquals(VaultFile.sha256(bytes))
                }.getOrDefault(false)
            }
            Notice.say(context.getString(if (ok) R.string.copy_saved else R.string.copy_failed))
        }
    }

    /** Everything open, unsaved changes too, into a new file that becomes the vault. */
    fun saveAs(to: Uri, then: () -> Unit) {
        Notice.say(context.getString(R.string.saving))
        scope.launch {
            val r = withContext(Dispatchers.IO) { Session.saveAs(context, to) }
            when (r) {
                Session.Saved.Done -> {
                    Notice.say(context.getString(R.string.saved_to_new_file))
                    then()
                }
                else -> Notice.say(context.getString(R.string.copy_failed))
            }
        }
    }

    /** The copy kept from before the last save, written back over the vault file. */
    fun putBackPrevious() {
        val open = Session.open ?: return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val previous = VaultFile.previous(context, open.uri) ?: return@withContext false
                val r = VaultFile(context, open.uri).write(previous, expected = null)
                r is VaultFile.Saved.Done
            }
            if (ok) {
                Session.lock()
                Notice.say(context.getString(R.string.previous_put_back))
            } else {
                Notice.say(context.getString(R.string.previous_failed))
            }
        }
    }
}

/** Reading a QR code from a picture, off the main thread. */
suspend fun readPicture(context: Context, uri: Uri): Recognised = withContext(Dispatchers.IO) {
    val texts = runCatching { Qr.read(context, uri) }.getOrDefault(emptyList())
    if (texts.isEmpty()) Recognised.Nothing else Import.recognise(texts.joinToString("\n"))
}
