package dji.sampleV5.aircraft

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
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
import org.webrtc.EglBase
import org.webrtc.NV21Buffer
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoTrack
import java.util.Random
import java.util.concurrent.TimeUnit

private val TAG = "CameraStreamActivity"

class CameraStreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var surface: Surface? = null

    private val permissionReqCode = 500 + Random().nextInt(100)

    private lateinit var binding: ActivityCameraStreamBinding

    private val viewModel: CameraStreamVM by viewModels()

    private val frameListener =
        ICameraStreamManager.CameraFrameListener { frameData, offset, length, width, height, format ->
            Log.d(
                "CameraStream",
                "Frame data size: " + frameData.size + " - (first 10 bytes): ${
                    frameData.take(10).joinToString("") { "%02x".format(it) }
                }"
            )
            modifyGreenChannel(frameData, offset, width, height)

            // draws the frame into the SurfaceView
            pushVideoToServer(frameData, offset, width, height)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel.initialize(this.application)

        binding.btnStart.setOnClickListener {
            viewModel.clickPublishBtn()
        }

        binding.btnEnd.setOnClickListener {
            viewModel.stopPublish()

            releaseSurfaceView()
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
        viewModel.videoTrackUpdate.observe(this) {
            if (it.useDroneCamera) {
                initializeVideoFromDrone(it.videoCapturer)
            } else {
                attachVideoToSurface(it.eglBase, it.videoTrack)
            }
        }
    }

    private fun attachVideoToSurface(eglBase: EglBase, videoTrack: VideoTrack) {
        val surfaceView = SurfaceViewRenderer(this)
        binding.root.addView(
            surfaceView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        surfaceView.init(eglBase.eglBaseContext, null)
        surfaceView.setMirror(true)
        videoTrack.addSink(surfaceView)
    }

    private fun initializeVideoFromDrone(videoSource: VideoCapturer) {
        val surfaceView = SurfaceView(this)
        binding.root.addView(
            surfaceView,
            0,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        surfaceView.tag = videoSource
        surfaceView.holder.addCallback(this)
    }

    private fun showMessage(msg: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            showToast(msg)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            ComponentIndexType.LEFT_OR_MAIN,
            ICameraStreamManager.FrameFormat.NV21,
            frameListener
        )

        MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(
            ComponentIndexType.LEFT_OR_MAIN,
            surface!!,
            binding.root.measuredWidth,
            binding.root.measuredHeight,
            ICameraStreamManager.ScaleType.CENTER_INSIDE
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
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

    private fun pushVideoToServer(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        if (binding.root.getChildAt(0) !is SurfaceView) {
            return
        }

        (binding.root.getChildAt(0).tag as? DJIVideoCapturer)?.let {
            if (!it.isCapturing || null == it.capturerObserver) return

            surface?.let { _ ->
                lifecycleScope.launch(Dispatchers.Main) {
                    try {
                        val nv21Buffer = NV21Buffer(frameData, width, height, null)
                        val timestampNS: Long =
                            TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
                        val videoFrame = VideoFrame(nv21Buffer, 0, timestampNS)
                        it.capturerObserver!!.onFrameCaptured(videoFrame)
                        videoFrame.release()
                    } catch (e: Exception) {
                        Log.e("CameraStream", "Failed to draw frame/set videoFrame: ${e.message}")
                    }
                }
            } ?: Log.e(
                "CameraStream",
                "Surface is null"
            )
        }
    }

    private fun releaseSurfaceView() {
        if (binding.root.getChildAt(0) is SurfaceView) {
            val surfaceView = binding.root.getChildAt(0) as SurfaceView
            binding.root.removeView(surfaceView)

            // release the surface view for integrated camera
            (surfaceView as? SurfaceViewRenderer)?.release()

            if (surfaceView.tag is VideoCapturer) {
                // using the drone camera
                MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(surfaceView.holder.surface)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cleanup
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
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