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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as CleosApp).container
        setContent {
            // null until the settings file has been read once (a few milliseconds);
            // drawing before that would flash the default wallpaper and colours.
            val settings: AppSettings? by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            settings?.let { s ->
                CleosTheme(s, container.images) { CleosNavHost() }
            }
        }
    }
}
