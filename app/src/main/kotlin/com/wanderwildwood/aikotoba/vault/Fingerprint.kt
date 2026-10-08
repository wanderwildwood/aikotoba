package com.wanderwildwood.aikotoba.vault

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opening the vault with the phone's fingerprint sensor instead of typing the master password.
 *
 * The way KeePassDX and KeePassXC's companions do it: the master password (and the key file,
 * if the vault needs one) is encrypted with a key that lives in the phone's hardware key
 * store and never leaves it. That key is made so that it can only be used right after a
 * fingerprint the phone already knows has been read (`setUserAuthenticationRequired`, strong
 * biometrics only, a fresh reading for every use), and the phone destroys it the moment a
 * fingerprint is added or removed (`setInvalidatedByBiometricEnrollment`). What this app keeps
 * on disk is the ciphertext and the random IV, in the no-backup directory, tied to the one
 * vault file it was made for. Nothing that could open the vault is stored in the clear, and
 * nothing here is of use on another phone or to anyone without a registered finger.
 *
 * It is off until Settings turns it on, with the vault open and one fingerprint reading to
 * encrypt. Turning it off deletes both the key and the ciphertext. The master password stays
 * the way in whenever the sensor is not: the password field is always there.
 */
object Fingerprint {

    private const val ALIAS = "fingerprint"
    private const val FILE = "fingerprint"
    private const val VERSION = 1

    /** The password and key file that open the vault, held for as long as the caller needs them. */
    class Secret(val password: String, val keyFile: ByteArray?)

    sealed class Available {
        object Yes : Available()
        /** No fingerprint sensor on this phone, or not one Android rates as strong. */
        object NoHardware : Available()
        /** A sensor, but no fingerprint registered on the phone yet. */
        object NoneEnrolled : Available()
        data class Other(val code: Int) : Available()
    }

    /** Whether the phone can do this at all, and if not, why. */
    fun available(context: Context): Available {
        val manager = context.getSystemService(BiometricManager::class.java) ?: return Available.NoHardware
        return when (val r = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Available.Yes
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Available.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Available.NoHardware
            else -> Available.Other(r)
        }
    }

    /** True when a fingerprint unlock has been set up, and for this vault file. */
    fun enrolledFor(context: Context, uri: Uri): Boolean {
        val f = file(context)
        if (!f.isFile) return false
        return runCatching { DataInputStream(f.inputStream()).use { readHeader(it).first } == uri.toString() }.getOrDefault(false)
    }

    /** Off: the key is gone from the key store and the ciphertext from the disk. */
    fun forget(context: Context) {
        runCatching { keyStore().deleteEntry(ALIAS) }
        file(context).delete()
    }

    /**
     * A cipher ready to encrypt for [uri], to be shown to the sensor. The key is made fresh each
     * time this is turned on, so a stale one (from a vault closed long ago) is never reused.
     * Throws when the phone will not make such a key (no fingerprint registered, no hardware).
     */
    fun cipherToEnrol(uri: Uri): Cipher {
        val ks = keyStore()
        ks.deleteEntry(ALIAS)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Every use needs a fresh fingerprint reading; the key is never unlocked for a while.
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                // A finger added or removed on the phone destroys the key: whoever changed the
                // fingerprints does not get the vault with them.
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        val key = generator.generateKey()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    /** After the sensor has unlocked [cipher]: the secret encrypted and written, with the IV, for [uri]. */
    fun enrol(context: Context, uri: Uri, cipher: Cipher, secret: Secret) {
        // The vault's address is bound into the ciphertext, so what was kept for one file never
        // opens as another's. It goes in only now: before the fingerprint has authorised the
        // operation, the key store refuses even this, and the cipher says so only at the end.
        cipher.updateAAD(uri.toString().toByteArray())
        val plain = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                val password = secret.password.toByteArray()
                writeInt(password.size)
                write(password)
                write(secret.keyFile ?: ByteArray(0))
                flush()
            }
        }.toByteArray()
        val sealed = cipher.doFinal(plain)
        plain.fill(0)
        val f = file(context)
        val tmp = File(f.parentFile, "$FILE.tmp")
        DataOutputStream(tmp.outputStream()).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(uri.toString())
            out.writeInt(cipher.iv.size)
            out.write(cipher.iv)
            out.writeInt(sealed.size)
            out.write(sealed)
            out.flush()
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw java.io.IOException("could not write")
        }
    }

    /**
     * A cipher ready to decrypt what was kept for [uri], to be shown to the sensor. Null when
     * nothing is kept for this vault. Throws [KeyPermanentlyInvalidatedException] when the
     * phone's fingerprints have changed since: the key is gone and so is the way in, until the
     * password is typed once and this is turned on again.
     */
    fun cipherToOpen(context: Context, uri: Uri): Cipher? {
        val f = file(context)
        if (!f.isFile) return null
        val (forUri, iv) = DataInputStream(f.inputStream()).use { readHeader(it) }
        if (forUri != uri.toString()) return null
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw KeyPermanentlyInvalidatedException()
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
    }

    /** After the sensor has unlocked [cipher]: the secret it was keeping. A wrong vault, or a tampered file, fails the GCM check. */
    fun open(context: Context, cipher: Cipher): Secret {
        val (forUri, sealed) = DataInputStream(file(context).inputStream()).use { input ->
            val (forUri, _) = readHeader(input)
            forUri to ByteArray(input.readInt()).also { input.readFully(it) }
        }
        cipher.updateAAD(forUri.toByteArray())
        val plain = cipher.doFinal(sealed)
        try {
            val input = DataInputStream(plain.inputStream())
            val password = ByteArray(input.readInt()).also { input.readFully(it) }
            val rest = input.readBytes()
            return Secret(String(password), rest.takeIf { it.isNotEmpty() })
        } finally {
            plain.fill(0)
        }
    }

    /** What happened at the sensor. */
    sealed class Read {
        class Done(val cipher: Cipher) : Read()
        /** The person backed out, or pressed the button for the password instead. */
        object Cancelled : Read()
        /** The sensor gave up (too many tries, a hardware fault): [message] is Android's own. */
        class Failed(val message: String) : Read()
    }

    /**
     * The phone's own fingerprint sheet over [context]'s window, unlocking [cipher] on a
     * reading that matches. [negative] is the button that leaves it: on the unlock screen,
     * "Use the password".
     */
    fun prompt(context: Context, title: String, subtitle: String?, negative: String, cipher: Cipher, onRead: (Read) -> Unit) {
        val executor = context.mainExecutor
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setNegativeButton(negative, executor) { _, _ -> onRead(Read.Cancelled) }
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(cipher),
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val unlocked = result.cryptoObject?.cipher
                    if (unlocked == null) onRead(Read.Failed("no cipher came back")) else onRead(Read.Done(unlocked))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        // The negative button reaches its own listener above, not this.
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                        -> onRead(Read.Cancelled)
                        else -> onRead(Read.Failed(errString.toString()))
                    }
                }
                // onAuthenticationFailed: a finger it did not know; the sheet stays up and says so itself.
            },
        )
    }

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** In the no-backup directory: it is of no use anywhere but this phone, and should not travel. */
    private fun file(context: Context): File = File(context.noBackupFilesDir, FILE)

    /** The vault's address and the IV; the stream is left at the ciphertext. */
    private fun readHeader(input: DataInputStream): Pair<String, ByteArray> {
        val version = input.readInt()
        if (version != VERSION) throw java.io.IOException("unknown version $version")
        val uri = input.readUTF()
        val iv = ByteArray(input.readInt()).also { input.readFully(it) }
        return uri to iv
    }
}
