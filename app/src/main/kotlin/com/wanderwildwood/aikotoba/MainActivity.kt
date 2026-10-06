package com.wanderwildwood.aikotoba

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.aikotoba.ui.App
import com.wanderwildwood.aikotoba.ui.monochrome

/**
 * The app's one window. Also where a vault file opened from Files arrives, and links or
 * pictures shared to it.
 */
class MainActivity : ComponentActivity() {

    private val incoming = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure(this)
        incoming.value = intent
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                App(incoming = incoming.value, onIncomingTaken = { incoming.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }

    companion object {
        /**
         * No screenshots, no picture in Recents, and nothing on these screens offered to an
         * autofill service, this one included: the master password is typed here.
         */
        fun secure(activity: ComponentActivity) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            activity.window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
    }
}
