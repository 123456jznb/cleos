package com.cleo.cleos

import android.service.notification.NotificationListenerService

/**
 * Only here so Cleos may see what is playing (ai/Music.kt): the system shows an app the phone's
 * media sessions when it has notification access. Notifications themselves are never looked at,
 * and the connection that would bring them is let go as soon as it is made. Reading the sessions
 * needs the access to be on, not the connection.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onListenerConnected() {
        requestUnbind()
    }
}
