package dji.sampleV5.aircraft

import android.app.Application
import dji.sampleV5.aircraft.log.FileLoggingTree
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels
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

    private var tree: FileLoggingTree? = null

    override fun onCreate() {
        super.onCreate()

        // Ensure initialization is called first
        msdkManagerVM.initMobileSDK(this)

        var file = File(this.getExternalFilesDir(null), "LOG")
        if (!file.exists()) {
            file.mkdirs()
        }
        logFile = File(file, "${SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(
            Date()
        )}.log")

        tree = FileLoggingTree(logFile)
        Timber.plant(tree!!)
    }

    override fun onTerminate() {
        super.onTerminate()
        tree?.destroy()
    }

    companion object {

        private lateinit var logFile: File

        fun getLogFile(): File {
            return logFile
        }
    }
}
