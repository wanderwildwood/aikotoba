package com.wanderwildwood.aikotoba.autofill

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * The browsers whose word on a page's site is believed, each pinned to the certificate it is
 * signed with, as KeePassDX and Bitwarden do. Answering a plain web address is not enough: a link
 * opener, a feed reader or a hostile app can have that filter too, show a sign-in form, name a
 * bank's site, and be handed its password. Any other app, browser or not, is treated as an app:
 * matched by an entry's claim on it, or chosen by hand. A browser not listed here loses only the
 * automatic match by site until it is added.
 */
object Browsers {
    /** Package to the SHA-256 digests (hex, upper case, no colons) of its signing certificates. */
    internal val KNOWN: Map<String, Set<String>> = mapOf(
        "com.android.chrome" to setOf("F0FD6C5B410F25CB25C3B53346C8972FAE30F8EE7411DF910480AD6B2D60DB83"),
        "org.mozilla.firefox" to setOf("A78B62A5165B4494B2FEAD9E76A280D22D937FEE6251AECE599446B2EA319B04"),
        "com.brave.browser" to setOf("9C2DB70513515FDCFBBC585B3EDF3D7123D4DC67C94FFC306350D4A96C5C9882"),
        "com.duckduckgo.mobile.android" to setOf("BB7BD56FB2D68E52882A8BE7E1CBAFF7B33769C4CB6534A9DD13ADD226A46E34"),
        "com.vivaldi.browser" to setOf("E8A78544655BA8C09817F732768F5689B1662EC4B2BC5A0BC0EC138D33CA3D1E"),
        "com.microsoft.emmx" to setOf("01E1999710A82C2749B4D50C445DC85D670B6136089D0A766A73827C82A1EAC9"),
        "com.sec.android.app.sbrowser" to setOf(
            "C8A2E9BCCF597C2FB6DC66BEE293FC13F2FC47EC77BC6B2B0D52C11F51192AB8",
            "34DF0E7A9F1CF1892E45C056B4973CD81CCF148A4050D11AEA4AC5A65F900A42",
        ),
    )

    fun isBrowser(context: Context, packageName: String): Boolean {
        if (packageName !in KNOWN) return false
        val certs = try {
            signers(context, packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        return pinned(packageName, certs)
    }

    /** Whether one of [certs] (a package's signing certificates) is pinned for [packageName]. */
    internal fun pinned(packageName: String, certs: List<ByteArray>, known: Map<String, Set<String>> = KNOWN): Boolean {
        val pins = known[packageName] ?: return false
        return certs.any { sha256(it) in pins }
    }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it.toInt() and 0xff) }

    /**
     * The certificates the installed package is signed with: every signer of an APK signed by
     * several, or the one signer and those it rotated from.
     */
    private fun signers(context: Context, packageName: String): List<ByteArray> {
        @Suppress("DEPRECATION") // the flags-object overload is Android 13; the Kompakt is 12
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return emptyList()
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return signatures.orEmpty().map { it.toByteArray() }
    }
}
