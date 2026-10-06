package com.wanderwildwood.aikotoba.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/**
 * The sign-in fields on a screen another app asks to have filled: which box takes the
 * user name and which the password, the app's package, and the site when the screen is a web page.
 */
data class Fields(
    val username: AutofillId?,
    val password: AutofillId?,
    val packageName: String,
    val webDomain: String?,
) {
    val ids: List<AutofillId> get() = listOfNotNull(username, password)
    val fillable: Boolean get() = password != null || username != null

    companion object {
        /**
         * Read from the screen's structure. Fields that say what they are (autofill hints, an
         * HTML input's type or autocomplete) are believed first; otherwise a password box is
         * known by its input type, and the user name is the last text box before it.
         */
        fun from(structure: AssistStructure): Fields {
            val nodes = ArrayList<AssistStructure.ViewNode>()
            // Where each field sits on the screen. Jetpack Compose lists its fields in the
            // structure in no particular order (a password box can come before the user name),
            // so "the box above the password" is decided by position, not by order.
            val top = HashMap<AssistStructure.ViewNode, Int>()
            var domain: String? = null
            fun walk(node: AssistStructure.ViewNode, y: Int) {
                val here = y + node.top - node.scrollY
                if (com.wanderwildwood.aikotoba.BuildConfig.DEBUG) {
                    // The shape of the other app's screen, never what is in its fields.
                    android.util.Log.d(
                        "aikotoba",
                        "node ${node.className} id=${node.idEntry} fid=${node.autofillId != null} type=${node.autofillType} " +
                            "hints=${node.autofillHints?.joinToString()} input=${node.inputType} vis=${node.visibility} top=$here",
                    )
                }
                if (domain == null && !node.webDomain.isNullOrBlank()) domain = node.webDomain
                if (node.autofillId != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE) {
                    nodes += node
                    top[node] = here
                }
                for (i in 0 until node.childCount) walk(node.getChildAt(i), here)
            }
            for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode, 0)

            var user = nodes.firstOrNull { kind(it) == Kind.USER }
            val pass = nodes.firstOrNull { kind(it) == Kind.PASSWORD }
            if (user == null && pass != null) {
                val passTop = top[pass] ?: 0
                user = nodes.filter { kind(it) == Kind.TEXT && (top[it] ?: 0) < passTop }.maxByOrNull { top[it] ?: 0 }
            }
            if (user == null && pass == null) {
                // A first page that asks only for the account ("Next" before the password).
                user = nodes.singleOrNull { kind(it) == Kind.TEXT && looksLikeAccount(it) }
            }
            return Fields(user?.autofillId, pass?.autofillId, structure.activityComponent.packageName, domain)
        }

        private enum class Kind { USER, PASSWORD, TEXT, OTHER }

        private fun kind(node: AssistStructure.ViewNode): Kind {
            val hints = node.autofillHints.orEmpty().map { it.lowercase() }
            if (hints.any { it.contains("password") }) return if (hints.any { it.contains("new") }) Kind.OTHER else Kind.PASSWORD
            if (hints.any { it == View.AUTOFILL_HINT_USERNAME.lowercase() || it == View.AUTOFILL_HINT_EMAIL_ADDRESS.lowercase() || it.contains("username") || it.contains("email") }) {
                return Kind.USER
            }
            node.htmlInfo?.let { html ->
                val attrs = html.attributes.orEmpty().associate { it.first.lowercase() to it.second.orEmpty().lowercase() }
                if (html.tag.equals("input", ignoreCase = true)) {
                    val type = attrs["type"].orEmpty()
                    val auto = attrs["autocomplete"].orEmpty()
                    if (type == "password") return if (auto.contains("new-password")) Kind.OTHER else Kind.PASSWORD
                    if (auto.contains("username") || auto.contains("email") || type == "email") return Kind.USER
                    if (type == "hidden" || type == "submit" || type == "button" || type == "checkbox" || type == "radio") return Kind.OTHER
                    return Kind.TEXT
                }
            }
            val input = node.inputType
            val cls = input and InputType.TYPE_MASK_CLASS
            val variation = input and InputType.TYPE_MASK_VARIATION
            if (cls == InputType.TYPE_CLASS_TEXT && (
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    )
            ) return Kind.PASSWORD
            if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return Kind.PASSWORD
            if (cls == InputType.TYPE_CLASS_TEXT && (
                    variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                    )
            ) return Kind.USER
            if (cls == InputType.TYPE_CLASS_TEXT || cls == 0) return Kind.TEXT
            return Kind.OTHER
        }

        private fun looksLikeAccount(node: AssistStructure.ViewNode): Boolean {
            val words = listOfNotNull(node.idEntry, node.hint, node.contentDescription?.toString()).joinToString(" ").lowercase()
            return listOf("user", "email", "e-mail", "login", "account", "mail").any { it in words }
        }
    }
}
