package com.cleo.cleos

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the app running while a TA finishes a reply the person left before it came. Phones freeze
 * an app in the background within seconds (ColorOS among them), and a reply streaming from the
 * model then stops mid-sentence: the person found replies cut off after switching away. A
 * foreground service is the one thing they leave running. It shows as "正在回你…" in the status
 * bar for as long as that takes and goes as soon as nothing is being written; what was written
 * comes as a notification (AppContainer). Started only on leaving the app with a reply under way,
 * never while the app is in front, so it doesn't flash up with every message.
 */
class ReplyKeeper : Service() {
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as CleosApp).container
        startForeground(ID, container.notifier.working(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watching == null) {
            watching = container.appScope.launch {
                // Until nothing is being written, or a while at most: a reply stuck on a model that
                // never answers must not keep the app up for good.
                withTimeoutOrNull(LIMIT_MS) { container.chat.working.first { it.isEmpty() } }
                withContext(Dispatchers.Main) { done() }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps how long this kind of service may run; long before that it is done anyway. */
    override fun onTimeout(startId: Int, fgsType: Int) = done()

    private fun done() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        watching?.cancel()
        watching = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val LIMIT_MS = 10 * 60_000L

        /**
         * On leaving the app (Activity.onStop). Allowed from there: an app just leaving the screen may
         * still start one. Anything else is caught and the reply simply takes its chances.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ReplyKeeper::class.java))
            } catch (_: Exception) {
            }
        }

        /** Back on screen: the app runs anyway. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ReplyKeeper::class.java))
        }
    }
}
