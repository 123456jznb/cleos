package com.cleo.cleos

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.cleo.cleos.ai.AiSelfAvatar
import com.cleo.cleos.ai.ChatClient
import com.cleo.cleos.ai.ChatRepository
import com.cleo.cleos.ai.Letters
import com.cleo.cleos.ai.PersonaMemory
import com.cleo.cleos.ai.Recaps
import com.cleo.cleos.ai.OpenMeteo
import com.cleo.cleos.ai.SecretRequests
import com.cleo.cleos.ai.ToolBox
import com.cleo.cleos.data.BackupService
import com.cleo.cleos.data.Companions
import com.cleo.cleos.data.ForeignImport
import com.cleo.cleos.data.ImageStore
import com.cleo.cleos.data.SecretStore
import com.cleo.cleos.data.SettingsRepository
import com.cleo.cleos.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    val companions = Companions(db, settings, secrets, images)
    val chatClient = ChatClient(http)
    val tools = ToolBox(
        db.todos(),
        db.diary(),
        OpenMeteo(http),
        requests = { id -> db.messages().requestsBy(id).mapNotNull { SecretRequests.decode(it.content) } },
        avatar = AiSelfAvatar(db, images, companions),
        letters = { id -> db.letters().allFor(id) },
        memories = db.memories(),
    )
    val recaps = Recaps(db, settings, secrets, chatClient, appScope)
    val chat = ChatRepository(db, settings, secrets, chatClient, tools, images, companions, recaps, appScope)
    val backup = BackupService(context, db, settings, images)
    val imports = ForeignImport(context, db, companions)
    val letters = Letters(db, settings, secrets, chatClient, appScope)

    init {
        // The first TA is made from the old settings before anything asks who is being talked to.
        appScope.launch {
            companions.ensure()
            // What a TA brought from another app used to sit in their persona; it moves into their memory, once.
            PersonaMemory.migrate(db, System.currentTimeMillis())
        }
    }
}
