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

    /**
     * A file (from another app, perhaps) asks for more key work than a phone can do: it would
     * sit on "Opening" for hours or ask for gigabytes. Refused, not handed to kotpass either.
     */
    class TooCostly : Exception("it asks for more work to open than a phone can do")

    /** Up to 1 GiB of memory. */
    const val MAX_MEMORY_KIB = 1L shl 20
    /** Memory times passes: 1 GiB 16 times, or 64 MiB 256 times. KeePassXC's defaults are far below. */
    const val MAX_WORK_KIB = 1L shl 24
    const val MAX_PARALLELISM = 64L

    /** Whether Argon2 at these costs is something a phone can finish. */
    fun withinPhone(memoryKib: Long, iterations: Long, parallelism: Long): Boolean =
        memoryKib in 1..MAX_MEMORY_KIB && iterations >= 1 && parallelism in 1..MAX_PARALLELISM &&
            iterations <= MAX_WORK_KIB / memoryKib

    private val argon2 by lazy { Argon2Kt() }

    override fun transformKey(kdfParameters: KdfParameters, compositeKey: ByteArray): ByteArray {
        val p = kdfParameters as? KdfParameters.Argon2 ?: throw Declined()
        // Checked before anything is declined: kotpass's own Argon2, the fallback, has no ceiling.
        val kib = p.memory / 1024u
        val memory = if (kib > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else kib.toLong()
        val iterations = if (p.iterations > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else p.iterations.toLong()
        if (!withinPhone(maxOf(memory, 1L), iterations, p.parallelism.toLong())) throw TooCostly()
        if (p.secretKey != null || p.associatedData != null) throw Declined()
        val version = when (p.version) {
            0x13u -> Argon2Version.V13
            0x10u -> Argon2Version.V10
            else -> throw Declined()
        }
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
            if (!withinPhone(params.memoryKib.toLong(), params.iterations.toLong(), params.parallelism.toLong())) throw TooCostly()
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
