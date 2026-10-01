package vn.lienson.boxstation

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

object AppLogger {
    private const val TAG = "BoxStation"
    private const val MAX_LOGS = 250
    private val buffer = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun i(tag: String, msg: String) {
        val line = "[${timeFormat.format(Date())}] 🟢 [$tag] $msg"
        Log.i(TAG, line)
        addLog(line)
    }

    fun d(tag: String, msg: String) {
        val line = "[${timeFormat.format(Date())}] 🔵 [$tag] $msg"
        Log.d(TAG, line)
        addLog(line)
    }

    fun w(tag: String, msg: String) {
        val line = "[${timeFormat.format(Date())}] 🟡 [$tag] $msg"
        Log.w(TAG, line)
        addLog(line)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val errStr = if (tr != null) " - ${tr.message}" else ""
        val line = "[${timeFormat.format(Date())}] 🔴 [$tag] $msg$errStr"
        Log.e(TAG, line, tr)
        addLog(line)
    }

    private fun addLog(line: String) {
        buffer.add(line)
        while (buffer.size > MAX_LOGS) {
            buffer.poll()
        }
    }

    fun getLogs(): List<String> = buffer.toList()

    fun getLogsText(): String = buffer.joinToString("\n")
}
