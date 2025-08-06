package dji.sampleV5.aircraft.log

import android.util.Log
import okhttp3.internal.closeQuietly
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLoggingTree (logFile: File): Timber.DebugTree() {

    private val writer = BufferedWriter(FileWriter(logFile, true))

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        super.log(priority, tag, message, t)
        writer.write("${dateFormat.format(Date())}\t$priority\t$tag\t\t$message\n")
        if (null != t) {
            writer.write(Log.getStackTraceString(t))
            writer.write("\n")
        }
        writer.flush()
    }

    fun destroy() {
        writer.flush()
        writer.closeQuietly()
    }
}