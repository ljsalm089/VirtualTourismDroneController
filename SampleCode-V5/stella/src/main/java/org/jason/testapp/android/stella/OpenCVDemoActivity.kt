package org.jason.testapp.android.stella

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Trace
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jason.testapp.android.stella.databinding.ActivityOpencvDemoBinding
import org.jason.testapp.android.stella.tracker.Camera2VisionTracker
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.Size
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Timer

private const val TAG = "OpenCVDemoActivity"

private fun DoubleArray.format(size: Int = 2): String {
    val sb = StringBuilder("{")
    this.forEach {
        sb.append(" %.0${size}f,".format(it))
    }
    if (sb.length > 1) sb.deleteCharAt(sb.length - 1)
    sb.append(" }")
    return sb.toString()
}

private fun FloatArray.format(size: Int = 2): String {
    val sb = StringBuilder("{")
    this.forEach {
        sb.append(" %.0${size}f,".format(it))
    }
    if (sb.length > 1) sb.deleteCharAt(sb.length - 1)
    sb.append(" }")
    return sb.toString()
}

class OpenCVDemoActivity : AppCompatActivity(), CameraBridgeViewBase.CvCameraViewListener2 {

    val binding by lazy {
        ActivityOpencvDemoBinding.inflate(layoutInflater)
    }

    var tracker: Camera2VisionTracker = Camera2VisionTracker(Size(640.0, 360.0), false)

    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    var mainDispatcher: CoroutineDispatcher = Dispatchers.Main

    var frameCount = 0L

    var hasInitialized = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(binding.root)
        binding.cameraView.visibility = View.VISIBLE
        binding.cameraView.setMaxFrameSize(1920, 1080)
        binding.cameraView.setCvCameraViewListener(this)


        if (checkAndRequestNecessaryPermissions()) {
            resumeAfterPermissionGranted()
        }

        // INFO initialize the tracker
        lifecycleScope.launch(ioDispatcher) {
            // step #1 extract the configuration and vocabulary files from the assets folder to sandbox folder
            val configFile: File = File(filesDir, "pixel_6_mono.yaml")
            if (!configFile.exists()) {
                copyFileFromRaw("pixel_6_mono.yaml", configFile.absolutePath)
            }

            val vocabFile = File(filesDir, "orb_vocab.fbow")
            if (!vocabFile.exists()) {
                copyFileFromRaw("orb_vocab.fbow", vocabFile.absolutePath)
            }

            tracker.initialize(configFile.absolutePath, vocabFile.absolutePath)
            tracker.setLoopDetector(true)
            tracker.startup()
            tracker.setMappingModule(true)
            hasInitialized = true
            Timber.d("initialize tracker done")
        }
    }

    override fun onResume() {
        super.onResume()

        loadOpenCVAndEnableCamera()

        if (hasInitialized) {
            Timber.d("onresume-> current position: ${tracker.getCurrentPosition().format()}")
            Timber.d("onresume-> current rotation: ${tracker.getCurrentRotation().format()}")
            Timber.d("onresume-> current tracking state: ${tracker.getTrackingState()}")
        }
    }


    override fun onPause() {
        super.onPause()

        binding.cameraView.disableView()
    }

    override fun onDestroy() {
        super.onDestroy()

        binding.cameraView.setCvCameraViewListener(null as? CameraBridgeViewBase.CvCameraViewListener2)
        binding.cameraView.disableView()

        tracker.destroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray,
        deviceId: Int
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
        if (requestCode == 0 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            binding.cameraView.setCameraPermissionGranted()
        }
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
    }

    override fun onCameraViewStopped() {
    }

    override fun onCameraFrame(inputFrame: CameraBridgeViewBase.CvCameraViewFrame?): Mat? {
        var result: Mat? = null
        if (hasInitialized) {

            inputFrame?.let {
                Trace.beginSection("VideoFrameProcess")
                try {
                    result = tracker.feedFrame(it)
                } finally {
                    Trace.endSection()
                }

                if (frameCount % 10 == 0L) {
                    lifecycleScope.launch(mainDispatcher) {
                        "Frame: #$frameCount\tPosition: ${
                            tracker.getCurrentPosition().format()
                        }\tRotation: ${
                            tracker.getCurrentRotation().format()
                        }\tState: ${tracker.getTrackingState()}".also { txt ->
                            binding.tvDebugInfo.text = txt
                        }
                    }
                }

                frameCount++
            }
        }
        return result ?: inputFrame?.rgba()
    }

    private fun resumeAfterPermissionGranted() {
        binding.cameraView.setCameraPermissionGranted()

        loadOpenCVAndEnableCamera()
    }

    private fun loadOpenCVAndEnableCamera() {
        if (OpenCVLoader.initDebug()) {
            binding.cameraView.enableView()
        }
    }

    private fun checkAndRequestNecessaryPermissions(): Boolean {
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)

        return if (cameraPermission == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 0)
            false
        }
    }

    private fun copyFileFromRaw(srcFileName: String, destPath: String) {
        var fs: InputStream? = null
        var outFs: OutputStream? = null
        try {
            fs = assets.open(srcFileName)
            outFs = File(destPath).outputStream()
            fs.copyTo(outFs)
        } finally {
            fs?.close()
            outFs?.close()
        }
    }
}