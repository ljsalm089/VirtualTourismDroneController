package dji.sampleV5.aircraft

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import dji.sampleV5.aircraft.log.ExactLevelLoggingTree
import dji.sampleV5.aircraft.log.FileLoggingTree
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels
import dji.sampleV5.aircraft.utils.LogLevel
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/3/1
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
open class DJIApplication : Application() {

    private val msdkManagerVM: MSDKManagerVM by globalViewModels()

    private val logConfig = listOf(
        MINIMUM_LOG_LEVEL to null,
        LogLevel.VERBOSE_DRONE_VELOCITY_READ_ACTIVELY to "velocity_changes_",
        LogLevel.VERBOSE_HEADSET_DRONE_VELOCITY_CHANGES to "drone_headset_velocity_changes_",
        LogLevel.VERBOSE_HEADSET_POSITION_CHANGES to "headset_position_changes_"
    )

    private val logTrees = ArrayList<Timber.Tree>(
        logConfig.size,
    )

    override fun onCreate() {
        super.onCreate()
        context = this

        // Ensure initialization is called first
        msdkManagerVM.initMobileSDK(this)

        initializeLog()
    }

    private fun initializeLog() {
        val file = File(this.getExternalFilesDir(null), "LOG")
        if (!file.exists()) {
            file.mkdirs()
        }
        val logSuffix = "${
            SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(
                Date()
            )
        }.log"

        for (config in logConfig) {
            val targetFile =
                if (!isLogInitialized() && (null == config.second)) {
                    logFile = File(file, logSuffix)
                    logFile
                } else {
                    File(file, "${config.second}${logSuffix}")
                }
            val tree = if (null == config.second) FileLoggingTree(
                targetFile,
                config.first
            ) else ExactLevelLoggingTree(targetFile, config.first)
            logTrees.add(tree)
            Timber.plant(tree)
        }

        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Timber.e(e)
            destroyLog()
        }
    }

    private fun destroyLog() {
        Timber.uprootAll()

        for (tree in logTrees) {
            (tree as? FileLoggingTree)?.destroy()
        }
        logTrees.clear()
    }

    override fun onTerminate() {
        super.onTerminate()
        destroyLog()
    }

    companion object {

        private lateinit var logFile: File

        @SuppressLint("StaticFieldLeak")
        private lateinit var context: Context

        private fun isLogInitialized() = ::logFile.isInitialized

        fun getLogFile(): File {
            return logFile
        }

        fun Int.idToString(): String {
            return context.getString(this)
        }
    }
}
