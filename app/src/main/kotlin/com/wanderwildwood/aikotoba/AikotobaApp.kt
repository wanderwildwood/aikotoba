package com.wanderwildwood.aikotoba

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.wanderwildwood.aikotoba.vault.NativeKdf
import com.wanderwildwood.aikotoba.vault.Session

class AikotobaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeKdf.installForKauth()
        Locker.start(this)
    }
}

/**
 * When the vault locks itself: as soon as the screen goes dark, and after a few minutes away
 * from the app (Settings chooses how many; none means on leaving).
 *
 * Going off to another app at this app's own asking (a file picker, the camera) gives the
 * person a little longer, so a file chosen does not come back to a locked vault.
 */
object Locker {
    private val handler = Handler(Looper.getMainLooper())
    private val lockNow = Runnable { Session.lock() }
    private var leftAt = 0L
    private var errand = false
    private lateinit var prefs: Prefs

    private const val ERRAND_MINUTES = 3

    fun start(app: Application) {
        prefs = Prefs(app)
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    handler.removeCallbacks(lockNow)
                    Session.lock()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                leftAt = SystemClock.elapsedRealtime()
                val minutes = allowed()
                handler.removeCallbacks(lockNow)
                if (minutes == 0) Session.lock() else handler.postDelayed(lockNow, minutes * 60_000L)
            }

            override fun onStart(owner: LifecycleOwner) {
                handler.removeCallbacks(lockNow)
                // The timer may not have run if the system paused the app; the clock still did.
                if (leftAt > 0 && SystemClock.elapsedRealtime() - leftAt >= allowed() * 60_000L) Session.lock()
                leftAt = 0
                errand = false
            }
        })
    }

    /** Call before starting another app for a result: the next trip away is an errand. */
    fun errand() {
        errand = true
    }

    private fun allowed(): Int = if (errand) maxOf(prefs.lockMinutes, ERRAND_MINUTES) else prefs.lockMinutes
}
