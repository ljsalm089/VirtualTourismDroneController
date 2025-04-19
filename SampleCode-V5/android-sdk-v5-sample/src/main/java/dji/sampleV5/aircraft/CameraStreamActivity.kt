package dji.sampleV5.aircraft

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dji.sampleV5.aircraft.databinding.ActivityCameraStreamBinding
import dji.sampleV5.aircraft.models.CameraStreamVM
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.NV21Buffer
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.TimeUnit


class CameraStreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var surface: Surface? = null

    private val cameraIndex =
        ComponentIndexType.LEFT_OR_MAIN // which camera we end up using MAY NEED TO TWEAK IF I HAVE THE WRONG CAM

    var frameWriter: FileOutputStream? = null

    val TAG = "CameraStreamActivity"

    private val permissionReqCode = 500 + Random().nextInt(100)

    private lateinit var binding: ActivityCameraStreamBinding

    private val viewModel: CameraStreamVM by viewModels()

    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat
        ) {
            Log.d(
                "CameraStream",
                "Frame data size: " + frameData.size + " - (first 10 bytes): ${
                    frameData.take(10).joinToString("") { "%02x".format(it) }
                }"
            )
            modifyGreenChannel(frameData, offset, width, height)
            Log.d("CameraStream", "")
            // draws the frame into the SurfaceView
            drawFrameOnSurface(
                frameData,
                offset,
                width,
                height
            )
        }
    }

    private lateinit var capturer: VideoCapturer

    override fun onCreate(savedInstanceState: Bundle?) { //
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            viewModel.clickPublishBtn()
        }

        binding.btnEnd.setOnClickListener {
            viewModel.stopPublish()
        }
        binding.btnSend.setOnClickListener {
            viewModel.sendData()
        }

        viewModel.requestPermissions.observe(this) {
            if (it.isNotEmpty()) {
                requestPermissions(it.toTypedArray(), permissionReqCode)
            }
        }
        viewModel.message.observe(this) {
            showMessage(it)
        }

        //allows event handling when stuff happens to the surface view such as is created, changed and destroyed. (every frame of teh camera will change it)
        binding.surfaceView.holder.addCallback(this)
    }

    private fun showMessage(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            showToast(msg)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            cameraIndex,
            ICameraStreamManager.FrameFormat.NV21,
            frameListener
        )

    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        //if we need to handle rotations and other stuff
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // clean up when the surface is destroyed
        surface = null
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
        Log.d("CameraStream", "frameListener removed")
    }

    private fun modifyGreenChannel(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        Log.d("CameraStream", "First line of modifyGreenChannel method")

        // Validate parameters first
        if (offset < 0) {
            Log.e("CameraStream", "Invalid negative offset: $offset")
            return
        }

        // Add counter for log throttling
        var iterationCount = 0

        // Process pixels assuming 4-byte format (e.g., RGBA)
        // Now starts from 0 instead of offset, with step=4
        for (i in 0 until (frameData.size - 1) step 4) {
            // Only log every 200,000 iterations
            if (iterationCount++ % 200000 == 0) {
                Log.d("CameraStream", "Processing iteration $iterationCount")
                Log.d("CameraStream", "i: $i")
            }

            // Safety check for array bounds
            if (i + 1 >= frameData.size) {
                Log.w("CameraStream", "Index out of bounds at i: $i")
                break
            }

            // Get and modify green channel
            val greenValue = frameData[i + 1]
            val newGreen = (greenValue + 20).coerceAtMost(0xFE).toByte()

            if (iterationCount % 200000 == 0) {
                Log.d("CameraStream", "Original green: $greenValue, New green: $newGreen")
            }

            frameData[i + 1] = newGreen
        }

        Log.d("CameraStream", "Completed processing. Total iterations: $iterationCount")
    }

    private fun drawFrameOnSurface(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        if (!this::capturer.isInitialized) {
            return
        }

        (capturer as? DJIVideoCapturer)?.let {
            if (!it.isCapturing || null == it.capturerObserver) return

            surface?.let { surface ->
                try {
                    Log.d(TAG, "starting the videoFrame making")
                    val nv21Buffer: NV21Buffer = NV21Buffer(frameData, width, height, null)
                    //ConversionUtils.convertARGB32ToI420(frameData, attr.width, attr.height)
                    Log.d(TAG, "buffer created: ")
                    val timestampNS: Long =
                        TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
                    Log.d(TAG, "timestamp: $timestampNS")
                    //val videoFrame = VideoFrame(i420Buffer, 0, timestampNS)
                    val videoFrame = VideoFrame(nv21Buffer, 0, timestampNS)
                    Log.d(TAG, "videoFrame made")
                    it.capturerObserver!!.onFrameCaptured(videoFrame)
                    Log.d(TAG, "sent frame to videoSource")
                    videoFrame.release()
                    Log.d(TAG, "release videoFrame")
                } catch (e: Exception) {
                    Log.e("CameraStream", "Failed to draw frame/set videoFrame: ${e.message}")
                }
            } ?: Log.e(
                "CameraStream",
                "Surface is null"
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cleanup
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
    }

    override fun onPause() {
        super.onPause()
        frameWriter?.close()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionReqCode) {
            viewModel.onRequestPermission(permissions.toList())
        }
    }

}

fun Activity.showToast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}