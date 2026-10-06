package com.wanderwildwood.aikotoba.vault

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyEntry
import app.keemobile.kotpass.database.modifiers.modifyGroup
import app.keemobile.kotpass.database.modifiers.moveEntry
import app.keemobile.kotpass.database.modifiers.removeEntry
import app.keemobile.kotpass.database.modifiers.withHistory
import app.keemobile.kotpass.database.modifiers.withRecycleBin
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import com.wanderwildwood.aikotoba.otp.OtpField
import com.wanderwildwood.aikotoba.otp.OtpSpec
import java.util.UUID

/** One entry as the screens show it. The password itself stays in the file's model until asked for. */
data class Item(
    val uuid: UUID,
    val title: String,
    val username: String,
    val url: String,
    val notes: String,
    /** Where it sits, "Email / Work"; empty at the top level. */
    val group: String,
    val otp: OtpSpec?,
    val hasPassword: Boolean,
    /** Every other field, by name: what autofill matching reads, and what is kept on edit. */
    val extra: Map<String, String>,
) {
    /** The name to list it under: the title, or failing that whatever else it has. */
    val label: String get() = title.ifBlank { username.ifBlank { url } }
}

/** What an edit screen hands back. [otp] is an `otpauth://` link, or empty for none. */
data class Draft(
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
    val otp: String,
)

/** Reading entries out of a database and making the few changes this app makes. */
object Vault {

    /** Every entry outside the recycle bin, in title order. */
    fun items(db: KeePassDatabase): List<Item> {
        val bin = db.content.meta.recycleBinUuid
        val out = ArrayList<Item>()
        fun walk(group: Group, path: List<String>) {
            if (bin != null && group.uuid == bin) return
            for (entry in group.entries) out += item(entry, path.joinToString(" / "))
            for (child in group.groups) walk(child, path + child.name)
        }
        walk(db.content.group, emptyList())
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun item(entry: Entry, group: String): Item {
        val plain: Map<String, String> = entry.fields.entries.associate { it.key to it.value.content }
        return Item(
            uuid = entry.uuid,
            title = plain[BasicField.Title()].orEmpty(),
            username = plain[BasicField.UserName()].orEmpty(),
            url = plain[BasicField.Url()].orEmpty(),
            notes = plain[BasicField.Notes()].orEmpty(),
            group = group,
            otp = OtpField.read(plain),
            hasPassword = entry.fields.password?.isEmpty() == false,
            extra = plain.filterKeys { it !in BasicField.keys && it != BasicField.Password() },
        )
    }

    /** The entry's password, read only when it is about to be shown, copied or filled. */
    fun password(db: KeePassDatabase, uuid: UUID): String =
        find(db, uuid)?.fields?.password?.content.orEmpty()

    fun find(db: KeePassDatabase, uuid: UUID): Entry? {
        var found: Entry? = null
        fun walk(group: Group) {
            if (found != null) return
            found = group.entries.firstOrNull { it.uuid == uuid }
            group.groups.forEach(::walk)
        }
        walk(db.content.group)
        return found
    }

    /** A new entry at the top level of the file. Returns the changed file and the entry's id. */
    fun add(db: KeePassDatabase, draft: Draft): Pair<KeePassDatabase, UUID> {
        val uuid = UUID.randomUUID()
        val entry = Entry(uuid = uuid, fields = fields(EntryFields.createDefault(), draft))
        val root = db.content.group.uuid
        return db.modifyGroup(root) { copy(entries = entries + entry) } to uuid
    }

    /**
     * An entry changed, keeping the old version in its history as KeePass does, and every field
     * this app does not show exactly as it was.
     */
    fun update(db: KeePassDatabase, uuid: UUID, draft: Draft): KeePassDatabase =
        db.modifyEntry(uuid) { withHistory { copy(fields = fields(fields, draft)) } }

    /** Only the code changed: a counter moved on, or a code attached to an existing entry. */
    fun setOtp(db: KeePassDatabase, uuid: UUID, link: String): KeePassDatabase =
        db.modifyEntry(uuid) {
            copy(fields = withOtp(fields, link))
        }

    /**
     * To the recycle bin, as KeePassXC does, so a deletion made on the phone can still be undone
     * on a computer. A file whose recycle bin is switched off deletes for good, as it would there.
     */
    fun delete(db: KeePassDatabase, uuid: UUID): KeePassDatabase {
        if (!db.content.meta.recycleBinEnabled) return db.removeEntry(uuid)
        return db.withRecycleBin { binUuid -> moveEntry(uuid, binUuid) }
    }

    private fun fields(old: EntryFields, draft: Draft): EntryFields {
        val base = old +
            (BasicField.Title() to EntryValue.Plain(draft.title.trim())) +
            (BasicField.UserName() to EntryValue.Plain(draft.username.trim())) +
            (BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString(draft.password))) +
            (BasicField.Url() to EntryValue.Plain(draft.url.trim())) +
            (BasicField.Notes() to EntryValue.Plain(draft.notes))
        return withOtp(base, draft.otp.trim())
    }

    private fun withOtp(fields: EntryFields, link: String): EntryFields {
        val cleared = fields - OtpField.KEY - OtpField.LEGACY_SEED - OtpField.LEGACY_SETTINGS
        return if (link.isEmpty()) cleared
        else cleared + (OtpField.KEY to EntryValue.Encrypted(EncryptedValue.fromString(link)))
    }
}
