package dji.sampleV5.aircraft

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dji.sampleV5.aircraft.databinding.ActivityCameraStreamBinding
import dji.sampleV5.aircraft.models.CameraStreamVM
import dji.sampleV5.aircraft.utils.BaseViewHolder
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

class StatusAdapter : RecyclerView.Adapter<BaseViewHolder>() {

    private val status = ArrayList<Pair<Int, Any?>>()

    init {
        status.addAll(listOf(
            R.string.hint_data_latency to -1,
            R.string.hint_fetch_video to -1,
            R.string.hint_push_video to -1,
            R.string.hint_control_frequency to -1,
            R.string.hint_empty to null,
            R.string.hint_drone_initial_position to "-/-/-",
            R.string.hint_drone_current_position to "-/-/-",
            R.string.hint_drone_distance_to_ip to "-"
        ))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return BaseViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_monitoring_status, parent, false));
    }

    override fun getItemCount(): Int = status.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        val text = holder.itemView as TextView

        val item = status[position]
        if (null == item.second) {
            text.setText(item.first)
        } else {
            val string = text.context.getString(item.first)
            text.text = "$string: ${item.second}"
        }
    }

    fun updateStatus(string: Int, data: Any?) {
        for(pos in 0..status.size) {
            if (status[pos].first == string) {
                status.removeAt(pos)
                status.add(pos, string to data)
                notifyItemChanged(pos)
                return
            }
        }

        status.add(string to data)
        notifyItemInserted(status.size - 1)
    }
}

class MessageAdapter : RecyclerView.Adapter<BaseViewHolder>() {

    private val message = ArrayList<Pair<String, Pair<Int, String>>>()
    private val colors = mapOf(
        Log.INFO to Color.WHITE,
        Log.WARN to Color.YELLOW,
        Log.ERROR to Color.RED
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message_info, parent, false)
        return BaseViewHolder(view)
    }

    override fun getItemCount(): Int = message.size

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        val timeTv: TextView? = holder.getView(R.id.tv_timestamp)
        val msgTv: TextView? = holder.getView(R.id.tv_log_info)

        timeTv?.text = message[position].first

        msgTv?.let {
            val item = message[position].second
            it.text = item.second
            it.setTextColor(colors[item.first] ?: Color.GRAY)
        }
    }

    fun appendMessage(level: Int, msg: String) {
        var timestamp = System.currentTimeMillis()
        val milliseconds = "%03d".format(timestamp % 1000)
        timestamp /= 1000
        val seconds = timestamp % 60
        timestamp /= 60
        val minutes = timestamp % 60
        timestamp /= 60
        val hours = timestamp % 24
        message.add("$hours:$minutes:$seconds $milliseconds" to (level to msg))
        this.notifyItemInserted(message.size - 1)
    }
}

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

        viewModel.stopBtnStatus.observe(this) {
            binding.btnEnd.isClickable = it
        }
        viewModel.publishBtnStatus.observe(this) {
            binding.btnStart.isClickable = it
        }
        viewModel.sendBtnStatus.observe(this) {
            binding.btnSend.isClickable = it
        }
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
        viewModel.videoTrackUpdate.observe(this) {
            if (it.useDroneCamera) {
                initializeVideoFromDrone(it.videoCapturer)
            } else {
                attachVideoToSurface(it.eglBase, it.videoTrack)
            }
        }

        arrayOf(binding.rvMessage, binding.rvStatus).forEach {
            it.layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
            it.setHasFixedSize(true)
        }
        binding.rvMessage.adapter = MessageAdapter()
        binding.rvStatus.adapter = StatusAdapter()

        lifecycleScope.launch {
            viewModel.message.collect { msg ->
                (binding.rvMessage.adapter as? MessageAdapter)?.appendMessage(msg.first, msg.second)
            }
        }
        lifecycleScope.launch {
            viewModel.monitoringStatus.collect { status ->
                (binding.rvStatus.adapter as? StatusAdapter)?.updateStatus(status.first, status.second)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // update layout margin if hasn't yet
        val params = binding.llInformation.layoutParams as? MarginLayoutParams
        if (params?.bottomMargin == 0) {
            params.bottomMargin = binding.llOperations.measuredHeight
            binding.llInformation.layoutParams = params
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
        surfaceView.setMirror(false)
        videoTrack.addSink(surfaceView)
        surfaceView.tag = videoTrack
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
                ""
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
            (surfaceView as? SurfaceViewRenderer)?.let {
                it.release()
                // avoid the memory leaking
                (it.tag as? VideoTrack)?.removeSink(it)
            }

            if (surfaceView.tag is VideoCapturer) {
                // using the drone camera
                MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(surfaceView.holder.surface)
            }
        }
    }

    override fun onDestroy() {
        releaseSurfaceView()

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