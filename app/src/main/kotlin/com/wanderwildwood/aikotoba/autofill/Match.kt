package com.wanderwildwood.aikotoba.autofill

import com.wanderwildwood.aikotoba.vault.Item
import java.net.URI

/**
 * Which entries belong to the app or web page asking to be filled.
 *
 * Only an exact claim counts, never a guess from names: an entry is offered to a browser's page
 * when its address is that site (or a part of it, `mail.example.org` for `example.org`), and to an app
 * when the entry names the app's package in the way KeePass2Android and KeePassDX write it
 * (`androidapp://<package>` in its URL or in a `KP2A_URL…` or `AndroidApp…` field). Everything
 * else is reached by searching, so a look-alike app cannot be handed a password by its name.
 */
object Match {

    const val APP_SCHEME = "androidapp://"

    fun matches(item: Item, packageName: String?, webDomain: String?): Boolean {
        val addresses = buildList {
            add(item.url)
            item.extra.forEach { (key, value) ->
                if (key.startsWith("KP2A_URL", ignoreCase = true) || key.startsWith("AndroidApp", ignoreCase = true)) add(value)
            }
        }.map { it.trim() }.filter { it.isNotEmpty() }

        if (webDomain != null && webDomain.isNotBlank()) {
            val domain = host(webDomain) ?: return false
            return addresses.any { address -> host(address)?.let { sameSite(it, domain) } == true }
        }
        if (packageName.isNullOrBlank()) return false
        return addresses.any { a ->
            a.equals(APP_SCHEME + packageName, ignoreCase = true) || a.equals(packageName, ignoreCase = true)
        }
    }

    /** The host of an address with or without a scheme, lower case, without a leading "www.". */
    fun host(address: String): String? {
        val a = address.trim()
        if (a.startsWith(APP_SCHEME, ignoreCase = true)) return null
        val withScheme = if (a.contains("://")) a else "https://$a"
        val host = runCatching { URI(withScheme).host }.getOrNull() ?: return null
        return host.lowercase().removePrefix("www.").trimEnd('.').takeIf { it.contains('.') || it == "localhost" }
    }

    /** One host is the other, or sits inside it on a label boundary. */
    fun sameSite(a: String, b: String): Boolean = a == b || a.endsWith(".$b") || b.endsWith(".$a")

    /** The address to add to an entry so the app named by [packageName] finds it next time. */
    fun claim(packageName: String) = APP_SCHEME + packageName
}
