package com.wanderwildwood.aikotoba.importer

import com.ok1cdj.kauth.core.Base32
import com.ok1cdj.kauth.core.GaMigration
import com.ok1cdj.kauth.core.Json
import com.ok1cdj.kauth.core.OtpAccount
import com.ok1cdj.kauth.core.OtpAlgorithm
import com.ok1cdj.kauth.core.OtpType
import com.ok1cdj.kauth.core.VaultCrypto
import com.ok1cdj.kauth.core.VaultModel
import com.ok1cdj.kauth.core.WrongPasswordException
import com.wanderwildwood.aikotoba.otp.OtpField
import com.wanderwildwood.aikotoba.otp.OtpSpec

/**
 * Codes brought in from another authenticator: what was found, what could not be read, and
 * whether any came through Google Authenticator's transfer format, which has no field for the
 * length of a code's time step (every one arrives as 30 seconds; see [fromGoogle]).
 */
data class Found(
    val codes: List<OtpSpec> = emptyList(),
    val failures: List<String> = emptyList(),
    val fromGoogle: Boolean = false,
) {
    operator fun plus(other: Found) = Found(codes + other.codes, failures + other.failures, fromGoogle || other.fromGoogle)
}

/** What a file or a pasted text turned out to be. */
sealed class Recognised {
    data class Codes(val found: Found) : Recognised()
    /** kAuth's encrypted backup: it needs kAuth's master password, see [Import.openKauth]. */
    data class KauthBackup(val blob: String) : Recognised()
    /** An Aegis export made with a password; Aegis can export without one. */
    object AegisEncrypted : Recognised()
    object Nothing : Recognised()
}

object Import {

    /** Whatever was pasted, shared or picked: links, a kAuth backup, an Aegis export. */
    fun recognise(text: String): Recognised {
        val trimmed = text.trim().removePrefix("﻿")
        if (trimmed.startsWith("{")) {
            val root = runCatching { Json.parseObject(trimmed) }.getOrNull()
            if (root != null) {
                if (root["kdf"] != null && root["wrapped"] != null) return Recognised.KauthBackup(trimmed)
                if (root["db"] != null || root["header"] != null) return aegis(root)
                if (root["accounts"] != null) {
                    // kAuth's own plain account list, as its tests and older versions write it.
                    val codes = VaultModel.deserialize(trimmed).map { OtpSpec(it) }
                    if (codes.isNotEmpty()) return Recognised.Codes(Found(codes))
                }
            }
        }
        val found = links(trimmed)
        return if (found.codes.isEmpty() && found.failures.isEmpty()) Recognised.Nothing else Recognised.Codes(found)
    }

    /**
     * Every `otpauth://` and `otpauth-migration://` link in [text], one per line or run together
     * in a sentence: kAuth's "Export accounts" file, a link copied from a website, a transfer QR
     * read out of a picture.
     */
    fun links(text: String): Found {
        var out = Found()
        val pattern = Regex("""otpauth(-migration)?://\S+""", RegexOption.IGNORE_CASE)
        for (match in pattern.findAll(text)) {
            val link = match.value.trimEnd('.', ',', ')', '"', '\'', '>')
            out += if (GaMigration.isMigration(link)) {
                val r = GaMigration.decode(link)
                Found(r.accounts.map { OtpSpec(it) }, r.failures, fromGoogle = true)
            } else {
                val spec = OtpField.parse(link)
                if (spec != null) Found(listOf(spec)) else Found(failures = listOf(short(link)))
            }
        }
        return out
    }

    /** kAuth's backup opened with kAuth's master password. Throws [WrongPasswordException]. */
    fun openKauth(blob: String, password: CharArray): Found {
        val plain = VaultCrypto.decrypt(blob, password)
        try {
            val accounts = VaultModel.deserialize(String(plain, Charsets.UTF_8))
            return Found(accounts.map { OtpSpec(it) })
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Aegis's plain (unencrypted) export: `db.entries[]`, each with a type and an `info` holding
     * the secret, algorithm, digits and period. Types other than TOTP, HOTP and Steam (Yandex,
     * mOTP) are named as not brought in.
     */
    private fun aegis(root: Map<String, Any?>): Recognised {
        val header = root["header"] as? Map<*, *>
        if (header?.get("slots") != null || root["db"] is String) return Recognised.AegisEncrypted
        val db = root["db"] as? Map<*, *> ?: return Recognised.Nothing
        val entries = db["entries"] as? List<*> ?: return Recognised.Nothing
        val codes = ArrayList<OtpSpec>()
        val failures = ArrayList<String>()
        for (e in entries) {
            val m = e as? Map<*, *> ?: continue
            val type = (m["type"] as? String)?.lowercase().orEmpty()
            val name = m["name"] as? String ?: ""
            val issuer = m["issuer"] as? String ?: ""
            val info = m["info"] as? Map<*, *>
            val secret = (info?.get("secret") as? String)?.replace(" ", "")?.uppercase().orEmpty()
            val label = listOf(issuer, name).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "?" }
            if (type !in setOf("totp", "hotp", "steam")) {
                failures += "$label ($type)"
                continue
            }
            if (!Base32.isValid(secret)) {
                failures += label
                continue
            }
            val steam = type == "steam"
            val account = OtpAccount(
                issuer = issuer,
                name = name,
                secret = secret,
                algorithm = OtpAlgorithm.from(info?.get("algo") as? String),
                digits = if (steam) 5 else (info?.get("digits") as? Double)?.toInt()?.takeIf { it in 1..9 } ?: 6,
                period = (info?.get("period") as? Double)?.toInt()?.takeIf { it > 0 } ?: 30,
                type = if (type == "hotp") OtpType.HOTP else OtpType.TOTP,
                counter = (info?.get("counter") as? Double)?.toLong() ?: 0L,
            )
            codes += OtpSpec(account, steam)
        }
        return Recognised.Codes(Found(codes, failures))
    }

    /** A link named in a failure, without its secret. */
    private fun short(link: String): String = link.substringBefore('?').take(80)

    /** True when [spec] makes the same codes as one already in [existing]. */
    fun isDuplicate(spec: OtpSpec, existing: List<OtpSpec>): Boolean = existing.any { OtpField.same(it, spec) }

    /** What an imported code is called as an entry: the issuer, or the account where there is none. */
    fun title(spec: OtpSpec): String = spec.account.issuer.ifBlank { spec.account.name }.ifBlank { "?" }
}
