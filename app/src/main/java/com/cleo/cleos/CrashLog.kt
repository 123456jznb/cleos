package com.cleo.cleos

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The last crash, kept on the phone to be read and sent on. Most people with the app can't plug
 * their phone into a computer, and "Cleos closes itself as it opens" (said of it with QQ 音乐 in the
 * background) doesn't say where. Installed first thing: it writes down what failed, in which
 * version on which phone, then lets the crash go on as it would have.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    /** More than any stack needs; a cause nested many times over is cut. */
    private const val MAX = 12_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(describe(app, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** What was written at the last crash; null when there hasn't been one since it was cleared. */
    fun read(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun describe(context: Context, thread: Thread, error: Throwable): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("Cleos $version，${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))} 闪退")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}），线程 ${thread.name}")
            append(stack.take(MAX))
        }
    }
}
