package com.wanderwildwood.aikotoba.vault

import android.content.Context
import android.net.Uri
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.modifiers.modifyMeta
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.Meta
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.BadPaddingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one open vault, while it is open. Locking drops it: what is left to reach the passwords
 * is the file, and the file needs the master password.
 *
 * Everything that reads or writes the file blocks (the key derivation alone can take seconds)
 * and is called off the main thread.
 */
object Session {

    class Open(
        val uri: Uri,
        val name: String,
        val db: KeePassDatabase,
        /** SHA-256 of the file as it was read or last written, to see when it changes underneath. */
        val hash: ByteArray,
        val writable: Boolean,
        /** True while there are changes in [db] the file does not have. */
        val unsaved: Boolean = false,
        /** The key file as chosen, if the vault needs one, so fingerprint unlock can keep it. */
        val keyFile: ByteArray? = null,
        /**
         * The master password as typed, masked in memory the way kotpass masks its own values
         * (kotpass itself keeps only its hash, which opens the file just as well but is not
         * what a person types). Held so fingerprint unlock can be turned on without asking.
         */
        val passphrase: EncryptedValue? = null,
    ) {
        val items: List<Item> by lazy { Vault.items(db) }
    }

    private val _state = MutableStateFlow<Open?>(null)
    val state: StateFlow<Open?> = _state
    val open: Open? get() = _state.value

    sealed class Opened {
        object Done : Opened()
        object WrongPassword : Opened()
        data class NotKeePass(val reason: String) : Opened()
        data class Unreadable(val reason: String) : Opened()
    }

    fun unlock(context: Context, uri: Uri, password: String, keyFile: ByteArray?): Opened {
        val file = VaultFile(context, uri)
        val bytes = try {
            file.read()
        } catch (e: Exception) {
            return Opened.Unreadable(e.message ?: e.javaClass.simpleName)
        }
        val credentials = try {
            credentials(password, keyFile)
        } catch (e: Exception) {
            return Opened.NotKeePass("the key file could not be read")
        }
        return try {
            val started = android.os.SystemClock.elapsedRealtime()
            val db = decode(bytes, credentials)
            // How long the key derivation and decryption took: a number, nothing from the vault.
            if (com.wanderwildwood.aikotoba.BuildConfig.DEBUG) {
                android.util.Log.i("aikotoba", "opened in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            }
            _state.value = Open(uri, file.name(), db, VaultFile.sha256(bytes), file.writable(), keyFile = keyFile, passphrase = EncryptedValue.fromString(password))
            Opened.Done
        } catch (e: CryptoError.InvalidKey) {
            Opened.WrongPassword
        } catch (e: CryptoError.InvalidCipherText) {
            Opened.WrongPassword
        } catch (e: BadPaddingException) {
            Opened.WrongPassword
        } catch (e: FormatError) {
            Opened.NotKeePass(e.message ?: e.javaClass.simpleName)
        } catch (e: OutOfMemoryError) {
            Opened.Unreadable("the file asks for more memory than the phone can give")
        } catch (e: Exception) {
            Opened.NotKeePass(e.message ?: e.javaClass.simpleName)
        }
    }

    /** A new, empty vault written to [uri] (a file just made by the system's "save as"). */
    fun create(context: Context, uri: Uri, password: String, title: String): Opened {
        val meta = Meta(generator = GENERATOR, name = title, recycleBinEnabled = true)
        val fresh = KeePassDatabase.Ver4x.create(title, meta, credentials(password, null))
        // Argon2id rather than kotpass's Argon2d default: the same memory and passes, without
        // the data-dependent memory access.
        val header = fresh.header
        val params = header.kdfParameters as KdfParameters.Argon2
        val db = fresh.copy(
            header = header.copy(kdfParameters = params.copy(variant = KdfParameters.Argon2.Variant.Argon2id, parallelism = 2u)),
        )
        val bytes = encode(db)
        val file = VaultFile(context, uri)
        return when (val r = file.write(bytes, expected = null)) {
            is VaultFile.Saved.Done -> {
                _state.value = Open(uri, file.name(), db, r.hash, file.writable(), passphrase = EncryptedValue.fromString(password))
                Opened.Done
            }
            is VaultFile.Saved.Failed -> Opened.Unreadable(r.reason)
            VaultFile.Saved.Changed -> Opened.Unreadable("the file changed while it was being made")
        }
    }

    fun lock() {
        _state.value = null
    }

    sealed class Saved {
        object Done : Saved()
        object ReadOnly : Saved()
        object Changed : Saved()
        data class Failed(val reason: String, val restored: Boolean) : Saved()
    }

    /**
     * [change] applied to the open vault and written to the file. The change is kept in memory
     * whatever happens to the write, marked unsaved until a write succeeds, so nothing typed is
     * lost to a file that could not be reached.
     */
    fun save(context: Context, force: Boolean = false, change: (KeePassDatabase) -> KeePassDatabase): Saved {
        val current = _state.value ?: return Saved.Failed("the vault is locked", restored = true)
        val db = change(current.db)
        val pending = Open(current.uri, current.name, db, current.hash, current.writable, unsaved = true, keyFile = current.keyFile, passphrase = current.passphrase)
        _state.value = pending
        if (!current.writable) return Saved.ReadOnly
        val bytes = try {
            encode(db)
        } catch (e: Exception) {
            return Saved.Failed(e.message ?: e.javaClass.simpleName, restored = true)
        }
        return when (val r = VaultFile(context, current.uri).write(bytes, if (force) null else current.hash)) {
            is VaultFile.Saved.Done -> {
                // Only if nothing else replaced the state meanwhile (a lock, another vault).
                if (_state.value === pending) {
                    _state.value = Open(current.uri, current.name, db, r.hash, current.writable, unsaved = false, keyFile = current.keyFile, passphrase = current.passphrase)
                }
                Saved.Done
            }
            VaultFile.Saved.Changed -> Saved.Changed
            is VaultFile.Saved.Failed -> Saved.Failed(r.reason, r.restored)
        }
    }

    /**
     * The open vault, unsaved changes and all, written to a new file at [uri], which then becomes
     * the vault. For a vault that came read-only (a server through Files) and has changes to keep.
     */
    fun saveAs(context: Context, uri: Uri): Saved {
        val current = _state.value ?: return Saved.Failed("the vault is locked", restored = true)
        val bytes = try {
            encode(current.db)
        } catch (e: Exception) {
            return Saved.Failed(e.message ?: e.javaClass.simpleName, restored = true)
        }
        val file = VaultFile(context, uri)
        return when (val r = file.write(bytes, expected = null)) {
            is VaultFile.Saved.Done -> {
                _state.value = Open(uri, file.name(), current.db, r.hash, file.writable(), keyFile = current.keyFile, passphrase = current.passphrase)
                Saved.Done
            }
            VaultFile.Saved.Changed -> Saved.Changed
            is VaultFile.Saved.Failed -> Saved.Failed(r.reason, r.restored)
        }
    }

    /** Throw away what is in memory and read the file again, with the same password. */
    fun reload(context: Context): Opened {
        val current = _state.value ?: return Opened.Unreadable("the vault is locked")
        val file = VaultFile(context, current.uri)
        return try {
            val bytes = file.read()
            val db = decode(bytes, current.db.credentials)
            _state.value = Open(current.uri, current.name, db, VaultFile.sha256(bytes), current.writable, keyFile = current.keyFile, passphrase = current.passphrase)
            Opened.Done
        } catch (e: CryptoError.InvalidKey) {
            Opened.WrongPassword
        } catch (e: Exception) {
            Opened.Unreadable(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The open vault's bytes as the file should be, for "save a copy" with unsaved changes in it. */
    fun encodeOpen(): ByteArray? = _state.value?.let { encode(it.db) }

    /** Whether the open vault also needs a key file; its password is then changed on a computer. */
    val hasKeyFile: Boolean get() = _state.value?.db?.credentials?.key != null

    /** What opened the vault, for fingerprint unlock to keep: the master password and the key file. */
    fun secret(): Fingerprint.Secret? {
        val current = _state.value ?: return null
        val password = current.passphrase?.text ?: return null
        if (current.db.credentials.key != null && current.keyFile == null) return null
        return Fingerprint.Secret(password, current.keyFile)
    }

    /** A new master password; the file is written with it at once. */
    fun changePassword(context: Context, password: String): Saved {
        val r = save(context) { db ->
            val credentials = credentials(password, null)
            when (db) {
                is KeePassDatabase.Ver3x -> db.copy(credentials = credentials)
                is KeePassDatabase.Ver4x -> db.copy(credentials = credentials)
            }.modifyMeta { copy(masterKeyChanged = Instant.now()) }
        }
        if (r == Saved.Done) {
            _state.value?.let { c ->
                _state.value = Open(c.uri, c.name, c.db, c.hash, c.writable, c.unsaved, c.keyFile, EncryptedValue.fromString(password))
            }
        }
        return r
    }

    fun decode(bytes: ByteArray, credentials: Credentials): KeePassDatabase = try {
        KeePassDatabase.decode(ByteArrayInputStream(bytes), credentials, kdfProvider = NativeKdf)
    } catch (e: NativeKdf.Declined) {
        KeePassDatabase.decode(ByteArrayInputStream(bytes), credentials)
    }

    fun encode(db: KeePassDatabase): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            db.encode(out, kdfProvider = NativeKdf, random = SecureRandom())
        } catch (e: NativeKdf.Declined) {
            out.reset()
            db.encode(out, random = SecureRandom())
        }
        return out.toByteArray()
    }

    private fun credentials(password: String, keyFile: ByteArray?): Credentials {
        val pass = EncryptedValue.fromString(password)
        return if (keyFile == null) Credentials.from(pass) else Credentials.from(pass, keyFile)
    }

    const val GENERATOR = "Passwords"
}
