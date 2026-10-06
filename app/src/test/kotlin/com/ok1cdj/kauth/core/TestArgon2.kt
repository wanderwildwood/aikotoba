package com.ok1cdj.kauth.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * BouncyCastle's Argon2id, exactly as kAuth itself calls it, for the JVM tests. On the phone
 * the app sets [VaultCrypto.argon2id] to the native Argon2 instead; a backup made by kAuth
 * opening on the phone is what shows the two agree.
 */
object TestArgon2 {
    fun bc(password: ByteArray, salt: ByteArray, params: VaultCrypto.KdfParams): ByteArray {
        val gen = Argon2BytesGenerator()
        gen.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(params.iterations)
                .withMemoryAsKB(params.memoryKib)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build(),
        )
        val out = ByteArray(VaultCrypto.KEY_BYTES)
        gen.generateBytes(password, out)
        return out
    }

    fun install() {
        VaultCrypto.argon2id = ::bc
    }
}
