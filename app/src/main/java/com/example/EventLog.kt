package com.example

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 极简运行日志环形缓冲：服务写、界面读。
 * 只保留最近若干条，进程被杀即清空，不写文件、不占存储。
 */
object EventLog {

    private const val MAX = 40

    private val entries = ArrayDeque<String>(MAX)
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(message: String) {
        val line = formatter.format(Date()) + "  " + message
        entries.addFirst(line)
        while (entries.size > MAX) {
            entries.removeLast()
        }
    }

    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
