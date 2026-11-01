package com.wanderwildwood.aikotoba.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.aikotoba.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/aikotoba"

/** What the APK carries that is not this app's own code, and the licence each project declares. */
val LICENCE_COMPONENTS: List<LicenceComponent> = listOf(
    LicenceComponent("kotpass", "the keemobile authors", "MIT", "https://github.com/keemobile/kotpass"),
    LicenceComponent("Argon2Kt", "Lambda Pioneer", "MIT", "https://github.com/lambdapioneer/argon2kt"),
    LicenceComponent("Argon2 reference implementation", "the Password Hashing Competition authors", "CC0-1.0 or Apache-2.0", "https://github.com/P-H-C/phc-winner-argon2"),
    LicenceComponent("Calm QR (the scanner)", "Jacob Moss", "Apache-2.0", "https://github.com/jacobrmoss/calm-qr"),
    LicenceComponent("ZXing", "the ZXing authors", "Apache-2.0", "https://github.com/zxing/zxing"),
    LicenceComponent("CameraX and Okio", "Google and Square", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/camera"),
    LicenceComponent("EFF large word list for passphrases", "Electronic Frontier Foundation", "CC-BY-3.0", "https://www.eff.org/dice"),
    LicenceComponent("kAuth (codes and imports)", "Ondrej Kolonicny (OK1CDJ)", "GPL-3.0-or-later", "https://github.com/ok1cdj/kAuth"),
    LicenceComponent("Mudita Mindful Design (MMD)", "Mudita", "Apache-2.0", "https://github.com/mudita/MMD"),
    LicenceComponent("Lato typeface, supplied with MMD", "\u0141ukasz Dziedzic", "OFL-1.1", "https://www.latofonts.com"),
    LicenceComponent("AndroidX and Jetpack Compose", "Google", "Apache-2.0", "https://developer.android.com/jetpack"),
    LicenceComponent("Kotlin and kotlinx.coroutines", "JetBrains", "Apache-2.0", "https://kotlinlang.org"),
    LicenceComponent("Material Symbols icons", "Google", "Apache-2.0", "https://fonts.google.com/icons"),
)

/** Each distinct licence text once: family id and the raw resource. */
val LICENCE_RAW: List<Pair<String, Int>> = listOf(
    "gpl" to R.raw.gpl_3_0,
    "apache" to R.raw.apache_2_0,
    "mit" to R.raw.mit,
    "ofl" to R.raw.ofl_1_1,
)

@Composable
fun rememberLicencesData(): LicencesData = LicencesData(
    appName = "Passwords",
    sourceUrl = SOURCE_URL,
    licenceLine = stringResource(R.string.licences_licence),
    components = LICENCE_COMPONENTS,
    texts = LICENCE_RAW.map { (id, raw) ->
        LicenceText(id, stringResource(when (id) {
            "gpl" -> R.string.licences_full_gpl
            "apache" -> R.string.licences_full_apache
            "mit" -> R.string.licences_full_mit
            "ofl" -> R.string.licences_full_ofl
            else -> R.string.licences_full_head
        }), raw)
    },
)

@Composable
fun rememberLicencesLabels(): LicencesLabels {
    val none = stringResource(R.string.about_no_browser_address, "@@")
    return LicencesLabels(
        title = stringResource(R.string.licences_title),
        back = stringResource(R.string.licences_back),
        sourceHeading = stringResource(R.string.licences_source_head),
        licenceHeading = stringResource(R.string.licences_licence_head),
        builtWithHeading = stringResource(R.string.licences_built_head),
        fullTextsHeading = stringResource(R.string.licences_full_head),
        noBrowser = { address -> none.replace("@@", address) },
    )
}
