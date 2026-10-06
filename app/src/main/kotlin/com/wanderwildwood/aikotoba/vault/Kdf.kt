package com.wanderwildwood.aikotoba.vault

import app.keemobile.kotpass.cryptography.format.KdfProvider
import app.keemobile.kotpass.database.header.KdfParameters
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import com.lambdapioneer.argon2kt.Argon2Version
import com.ok1cdj.kauth.core.VaultCrypto

/**
 * Argon2 through the reference C implementation (Argon2Kt), in place of the pure-Kotlin one
 * kotpass carries.
 *
 * A vault made by KeePassXC on a computer is tuned to take about a second there, and the same
 * work in pure Kotlin on a phone's small cores runs to tens of seconds. The C code is several
 * times quicker on one core and uses every lane the file asks for. Nothing here computes
 * anything itself: it hands kotpass's parameters to Argon2Kt unchanged.
 *
 * What Argon2Kt cannot take, a secret key or associated data (KeePass never writes either), it
 * declines with [Declined], and the vault is opened again with kotpass's own Argon2. AES-KDF is
 * always kotpass's.
 */
object NativeKdf : KdfProvider {

    /** This provider will not do these parameters; the caller falls back to kotpass's own. */
    class Declined : Exception("not handled by the native Argon2")

    private val argon2 by lazy { Argon2Kt() }

    override fun transformKey(kdfParameters: KdfParameters, compositeKey: ByteArray): ByteArray {
        val p = kdfParameters as? KdfParameters.Argon2 ?: throw Declined()
        if (p.secretKey != null || p.associatedData != null) throw Declined()
        val version = when (p.version) {
            0x13u -> Argon2Version.V13
            0x10u -> Argon2Version.V10
            else -> throw Declined()
        }
        val kib = p.memory / 1024u
        if (kib == 0uL || kib > Int.MAX_VALUE.toULong() || p.iterations > Int.MAX_VALUE.toULong() ||
            p.parallelism > Int.MAX_VALUE.toUInt()
        ) {
            throw Declined()
        }
        val mode = when (p.variant) {
            KdfParameters.Argon2.Variant.Argon2d -> Argon2Mode.ARGON2_D
            KdfParameters.Argon2.Variant.Argon2id -> Argon2Mode.ARGON2_ID
        }
        return argon2.hash(
            mode = mode,
            password = compositeKey,
            salt = p.salt.toByteArray(),
            tCostInIterations = p.iterations.toInt(),
            mCostInKibibyte = kib.toInt(),
            parallelism = p.parallelism.toInt(),
            hashLengthInBytes = 32,
            version = version,
        ).rawHashAsByteArray()
    }

    /** kAuth's backups use Argon2id; they get the same native code. */
    fun installForKauth() {
        VaultCrypto.argon2id = { password, salt, params ->
            argon2.hash(
                mode = Argon2Mode.ARGON2_ID,
                password = password,
                salt = salt,
                tCostInIterations = params.iterations,
                mCostInKibibyte = params.memoryKib,
                parallelism = params.parallelism,
                hashLengthInBytes = VaultCrypto.KEY_BYTES,
                version = Argon2Version.V13,
            ).rawHashAsByteArray()
        }
    }
}
