package com.wanderwildwood.aikotoba

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Toast

/**
 * Copying a password or a code, and taking it off the clipboard thirty seconds later.
 *
 * Two clocks, because either can fail alone: a timer inside the app, which stops if the system
 * ends the app in the background, and an alarm, which the system keeps but which a background
 * manager that force-stops the app (DuraSpeed on the Kompakt) cancels. The clip is marked
 * sensitive, so a keyboard that keeps a clipboard history and honours the mark leaves it out.
 */
object Clipboard {

    const val SECONDS = 30
    private val handler = Handler(Looper.getMainLooper())

    fun copy(context: Context, label: String, value: String) {
        val app = context.applicationContext
        val cm = app.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, value)
        // ClipDescription.EXTRA_IS_SENSITIVE, by its value: the name is Android 13's, the
        // keyboards that honour it read the same key on 12.
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        handler.removeCallbacksAndMessages(TOKEN)
        handler.postAtTime({ clearNow(app) }, TOKEN, SystemClock.uptimeMillis() + SECONDS * 1000L)
        runCatching {
            app.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + SECONDS * 1000L,
                pending(app),
            )
        }
        Toast.makeText(app, app.getString(R.string.copied, label, SECONDS.toString()), Toast.LENGTH_SHORT).show()
    }

    /**
     * Empty the clipboard. It does not look first at what is there, because an app in the
     * background is not allowed to read it; whatever was copied in the thirty seconds after is
     * cleared too.
     */
    fun clearNow(context: Context) {
        handler.removeCallbacksAndMessages(TOKEN)
        runCatching { context.getSystemService(AlarmManager::class.java).cancel(pending(context)) }
        val cm = context.getSystemService(ClipboardManager::class.java)
        try {
            cm.clearPrimaryClip()
        } catch (e: Exception) {
            runCatching { cm.setPrimaryClip(ClipData.newPlainText("", "")) }
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ClearReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val TOKEN = Any()

    /** The alarm's end of [copy]; not exported, so only the alarm this app set can reach it. */
    class ClearReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            clearNow(context.applicationContext)
        }
    }
}
