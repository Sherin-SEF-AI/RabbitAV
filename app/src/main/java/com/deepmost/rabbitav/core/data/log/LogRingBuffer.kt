package com.deepmost.rabbitav.core.data.log

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * In-memory ring buffer of recent log lines, exportable from the debug screen.
 * Capacity 4000 lines (~500 KB worst case) keeps several minutes of drive logs
 * without meaningful memory pressure.
 */
@Singleton
class LogRingBuffer @Inject constructor() {

    private val capacity = 4000
    private val lines = ArrayDeque<String>(capacity)
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val tree: Timber.Tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val pri = when (priority) {
                android.util.Log.VERBOSE -> "V"
                android.util.Log.DEBUG -> "D"
                android.util.Log.INFO -> "I"
                android.util.Log.WARN -> "W"
                android.util.Log.ERROR -> "E"
                else -> "?"
            }
            val line = buildString {
                append(timeFormat.format(Date()))
                append(' ').append(pri).append('/')
                append(tag ?: "RabbitAV")
                append(": ").append(message)
                if (t != null) {
                    append('\n').append(android.util.Log.getStackTraceString(t))
                }
            }
            synchronized(lock) {
                if (lines.size >= capacity) lines.removeFirst()
                lines.addLast(line)
            }
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun exportText(): String = snapshot().joinToString("\n")

    fun clear() = synchronized(lock) { lines.clear() }
}
