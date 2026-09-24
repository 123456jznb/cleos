package com.cleo.cleos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleo.cleos.data.AppSettings
import com.cleo.cleos.ui.CleosNavHost
import com.cleo.cleos.ui.theme.CleosTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as CleosApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // null until the settings file has been read once (a few milliseconds);
            // drawing before that would flash the default wallpaper and colours.
            val settings: AppSettings? by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { s ->
                CleosTheme(s, container.images) { CleosNavHost() }
            }
        }
    }

    // Letters due get written when the app comes to the front: a background job would be
    // killed on many phones, so nothing waits for one.
    override fun onResume() {
        super.onResume()
        container.letters.tick()
    }
}
