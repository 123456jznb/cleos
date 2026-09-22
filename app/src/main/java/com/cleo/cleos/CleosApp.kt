package com.cleo.cleos

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.cleo.cleos.ai.ChatClient
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class CleosApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-made dependency wiring; the app is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    /** Outlives any screen: saves and replies started here finish even if the user leaves. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "cleos.db").build()
    val settings = SettingsRepository(context)
    val secrets = SecretStore(context)
    val images = ImageStore(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // Reasoning models can think for a long time before the first visible token.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val chatClient = ChatClient(http)
    val chat = ChatRepository(db, settings, secrets, chatClient, appScope)
}
