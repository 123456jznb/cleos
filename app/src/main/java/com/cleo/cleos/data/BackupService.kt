package com.cleo.cleos.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.ConversationEntity
import com.cleo.cleos.data.db.DiaryEntryEntity
import com.cleo.cleos.data.db.MessageEntity
import com.cleo.cleos.data.db.TodoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupSettings(
    val apiBaseUrl: String,
    val apiModel: String,
    val aiName: String,
    val userName: String,
    val persona: String,
    val historySize: Int,
    val wallpaper: String?,
    val glassMode: String,
    val wallpaperDark: Boolean?,
    val wallpaperHue: Float?,
    val wallpaperChroma: Float?,
)

/** The backup format: one zip, `backup.json` plus the pictures under `images/`. */
@Serializable
data class BackupFile(
    // No defaults on decode: a file without these is not one of ours.
    val format: String,
    val version: Int,
    val exportedAt: Long,
    val settings: BackupSettings,
    val conversations: List<ConversationEntity>,
    val messages: List<MessageEntity>,
    val diary: List<DiaryEntryEntity>,
    val todos: List<TodoEntity>,
) {
    companion object {
        const val FORMAT = "cleos-backup"
        const val VERSION = 1
    }
}

data class BackupSummary(val conversations: Int, val messages: Int, val diary: Int, val todos: Int, val images: Int) {
    override fun toString() = "$conversations 段对话（$messages 条消息）、$diary 篇日记、$todos 条待办、$images 张图"
}

class BackupException(message: String) : Exception(message)

/**
 * Export and restore. The API key is never written out: it only exists encrypted with a
 * key that cannot leave this phone, and a backup file travels (chat apps, cloud drives).
 *
 * Restore replaces everything, so it is careful about order:
 *  1. read and check the whole file before touching anything;
 *  2. snapshot what is there now (`before-restore.zip`), so a wrong file can be undone;
 *  3. copy pictures in, then swap the database contents in one transaction. If any row
 *     is bad the transaction rolls back and the old data is still there.
 */
class BackupService(
    context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val images: ImageStore,
) {
    private val resolver = context.contentResolver
    private val cacheDir = context.cacheDir
    private val snapshot = File(context.filesDir, "backups/before-restore.zip")
    // encodeDefaults: format and version have default values, and without this they are
    // silently left out of the file, which makes the "is this our backup, which version"
    // check on restore pass for anything.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val hasSnapshot: Boolean get() = snapshot.exists()

    suspend fun export(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val out = resolver.openOutputStream(uri) ?: throw BackupException("打不开要保存的位置")
        out.use { write(it) }
    }

    suspend fun restore(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw BackupException("打不开这个文件")
        input.use { restoreFrom(it, takeSnapshot = true) }
    }

    /** Puts back what was there before the last restore. */
    suspend fun undoRestore(): BackupSummary = withContext(Dispatchers.IO) {
        if (!snapshot.exists()) throw BackupException("没有可以撤销的恢复")
        val summary = snapshot.inputStream().use { restoreFrom(it, takeSnapshot = false) }
        snapshot.delete()
        summary
    }

    private suspend fun write(raw: OutputStream): BackupSummary {
        val s = settings.current()
        val data = BackupFile(
            format = BackupFile.FORMAT,
            version = BackupFile.VERSION,
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                apiBaseUrl = s.apiBaseUrl,
                apiModel = s.apiModel,
                aiName = s.aiName,
                userName = s.userName,
                persona = s.persona,
                historySize = s.historySize,
                wallpaper = s.wallpaper,
                glassMode = s.glassMode.name,
                wallpaperDark = s.wallpaperDark,
                wallpaperHue = s.wallpaperHue,
                wallpaperChroma = s.wallpaperChroma,
            ),
            conversations = db.conversations().all(),
            messages = db.messages().all(),
            diary = db.diary().all(),
            todos = db.todos().all(),
        )
        val pictures = (data.diary.flatMap { e -> DiaryBlocks.images(DiaryBlocks.decode(e.blocks)).map { it.file } } +
            listOfNotNull(s.wallpaper)).toSet()

        var written = 0
        ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_NAME))
            zip.write(json.encodeToString(data).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (name in pictures) {
                val f = images.file(name)
                if (!f.exists()) continue
                zip.putNextEntry(ZipEntry("images/$name"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                written++
            }
        }
        return BackupSummary(data.conversations.size, data.messages.size, data.diary.size, data.todos.size, written)
    }

    private suspend fun restoreFrom(input: InputStream, takeSnapshot: Boolean): BackupSummary {
        val staging = File(cacheDir, "restore-${System.nanoTime()}").apply { mkdirs() }
        try {
            var data: BackupFile? = null
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == JSON_NAME -> {
                            data = runCatching { json.decodeFromString<BackupFile>(zip.readBytes().decodeToString()) }
                                .getOrElse { throw BackupException("备份文件坏了，读不出来") }
                        }
                        entry.name.startsWith("images/") && !entry.isDirectory -> {
                            // Only the bare file name is used: a crafted archive with
                            // "images/../../somewhere" must not write outside the staging dir.
                            val name = File(entry.name).name
                            if (name.isNotBlank() && !name.startsWith(".")) {
                                File(staging, name).outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                }
            }
            val d = data ?: throw BackupException("这不是 Cleos 的备份文件")
            if (d.format != BackupFile.FORMAT) throw BackupException("这不是 Cleos 的备份文件")
            if (d.version > BackupFile.VERSION) throw BackupException("这份备份来自更新版本的 Cleos，先更新 App 再恢复")

            if (takeSnapshot) {
                snapshot.parentFile?.mkdirs()
                val tmp = File(snapshot.path + ".tmp")
                tmp.outputStream().use { write(it) }
                tmp.renameTo(snapshot)
            }

            val pictures = staging.listFiles().orEmpty()
            for (f in pictures) {
                val dest = images.file(f.name)
                if (!dest.exists()) f.copyTo(dest)
            }
            db.withTransaction {
                db.messages().clear()
                db.conversations().clear()
                db.diary().clear()
                db.todos().clear()
                db.conversations().insertAll(d.conversations)
                db.messages().insertAll(d.messages)
                db.diary().insertAll(d.diary)
                db.todos().insertAll(d.todos)
            }
            val bs = d.settings
            settings.update {
                it.copy(
                    apiBaseUrl = bs.apiBaseUrl,
                    apiModel = bs.apiModel,
                    aiName = bs.aiName,
                    userName = bs.userName,
                    persona = bs.persona,
                    historySize = bs.historySize,
                    wallpaper = bs.wallpaper?.takeIf { name -> images.file(name).exists() },
                    glassMode = runCatching { GlassMode.valueOf(bs.glassMode) }.getOrDefault(GlassMode.Auto),
                    wallpaperDark = bs.wallpaperDark,
                    wallpaperHue = bs.wallpaperHue,
                    wallpaperChroma = bs.wallpaperChroma,
                )
            }
            settings.setCurrentConversation(null)
            return BackupSummary(d.conversations.size, d.messages.size, d.diary.size, d.todos.size, pictures.size)
        } finally {
            staging.deleteRecursively()
        }
    }

    private companion object {
        const val JSON_NAME = "backup.json"
    }
}
