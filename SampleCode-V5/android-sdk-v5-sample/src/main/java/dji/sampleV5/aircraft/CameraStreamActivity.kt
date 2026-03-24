package dji.sampleV5.aircraft

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
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
import dji.sampleV5.aircraft.DJIApplication.Companion.idToString
import dji.sampleV5.aircraft.databinding.ActivityCameraStreamBinding
import dji.sampleV5.aircraft.models.CameraStreamVM
import dji.sampleV5.aircraft.utils.BaseViewHolder
import dji.sampleV5.aircraft.utils.format
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import java.util.Random

class StatusAdapter(val context: Context) : RecyclerView.Adapter<BaseViewHolder>() {

    private val statusList = ArrayList<Pair<String, String>>()

    init {
        statusList.addAll(
            listOf(
                R.string.hint_data_latency.idToString() to "-1",
                R.string.hint_fetch_video.idToString() to "-1",
                R.string.hint_push_video.idToString() to "-1",
                R.string.hint_control_frequency.idToString() to "-1",
                R.string.hint_empty.idToString() to "",
                R.string.hint_drone_connected.idToString() to "-",
                R.string.hint_connection_signal.idToString() to "N/A",
                R.string.hint_connection_capability.idToString() to "N/A",
                R.string.hint_empty.idToString() to "",
                R.string.hint_flight_control_connected.idToString() to "-",
                R.string.hint_virtual_stick_state.idToString() to "-/-",
                R.string.hint_flight_mode.idToString() to "N/A",
                R.string.hint_remote_controller_flight_mode.idToString() to "N/A",
                R.string.hint_remote_control.idToString() to "N/A",
                )
        )
        if (BuildConfig.DEBUG) {
            statusList.addAll(
                listOf(
                    R.string.hint_empty.idToString() to "",
                    R.string.hint_is_in_simulator_mode.idToString() to "-",
                    R.string.hint_simulator_state.idToString() to "-"
                )
            )
        }
        statusList.addAll(
            listOf(
                R.string.hint_empty.idToString() to "",
                R.string.hint_drone_current_position.idToString() to "-/-/-",
                R.string.hint_drone_attitude.idToString() to "-/-/-",
                R.string.hint_drone_tracking_state.idToString() to "-",
                R.string.hint_empty.idToString() to "",
            )
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return BaseViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_monitoring_status, parent, false)
        );
    }

    override fun getItemCount(): Int = statusList.size

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        val text = holder.itemView as TextView

        val item = statusList[position]
        if (item.second.isEmpty()) {
            text.text = item.first
        } else {
            text.text = "${item.first}: ${item.second}"
        }
    }

    fun updateStatus(string: String, data: String) {
        for (pos in 0..statusList.size - 1) {
            if (statusList[pos].first == string) {
                statusList.removeAt(pos)
                statusList.add(pos, string to data)
                notifyItemChanged(pos)
                return
            }
        }

        statusList.add(string to data)
        notifyItemInserted(statusList.size - 1)
    }
}

class MessageAdapter : RecyclerView.Adapter<BaseViewHolder>() {

    private val message = ArrayList<Pair<String, Pair<Int, String>>>()
    private val colors = mapOf(
        Log.INFO to Color.WHITE,
        Log.WARN to Color.YELLOW,
        Log.ERROR to Color.RED,
        Log.ASSERT to Color.RED
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val view =
            LayoutInflater.from(parent.context).inflate(R.layout.item_message_info, parent, false)
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
        val seconds = (timestamp % 60).toInt().format()
        timestamp /= 60
        val minutes = (timestamp % 60).toInt().format()
        timestamp /= 60
        val hours = (timestamp % 24).toInt().format()
        message.add("$hours:$minutes:$seconds ${milliseconds.toInt().format(3)}" to (level to msg))
        this.notifyItemInserted(message.size - 1)
    }
}

fun TextView.updateTextColor(enable: Boolean?) {
    this.setTextColor(if (enable == true) Color.WHITE else Color.GRAY)
}

class CameraStreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var surface: Surface? = null

    private val permissionReqCode = 500 + Random().nextInt(100)

    private lateinit var binding: ActivityCameraStreamBinding

    private val viewModel: CameraStreamVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStartPublishing.setOnClickListener {
            viewModel.clickPublishBtn()
        }

        binding.btnStopPublishing.setOnClickListener {
            viewModel.stopPublish()

            releaseSurfaceView()
        }
        binding.btnGetReadyToControl.setOnClickListener {
            viewModel.getReadyForRemoteControl()
        }
        binding.btnAbortRemoteControl.setOnClickListener {
            viewModel.abortDroneControl()
        }
        binding.btnLandOff.setOnClickListener {
            viewModel.landOffDrone()
        }
        listOf(
            binding.btnForward,
            binding.btnBackward,
            binding.btnLeft,
            binding.btnRight,
            binding.btnReset,
            binding.btnRotateLeft,
            binding.btnRotateRight,
            binding.btnRiseGimbal,
            binding.btnSetGimbal
        ).forEach { btn ->
            btn.setOnClickListener { view->
                viewModel.flightToDirection(btn.id)
            }
            btn.visibility = View.GONE
        }
        binding.imgScrollToBottom.setOnClickListener {
            (binding.rvMessage.layoutManager as? LinearLayoutManager)?.scrollToPosition(
                binding.rvMessage.adapter!!.itemCount - 1
            )
        }

        viewModel.stopPublishingBtnStatus.observe(this) {
            binding.btnStopPublishing.updateTextColor(it)
        }
        viewModel.publishBtnStatus.observe(this) {
            binding.btnStartPublishing.updateTextColor(it)
        }
        viewModel.startControlBtnStatus.observe(this) {
            binding.btnGetReadyToControl.updateTextColor(it)
        }
        viewModel.abortControlBtnStatus.observe(this) {
            binding.btnAbortRemoteControl.updateTextColor(it)
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
        val layoutManager = binding.rvMessage.layoutManager as LinearLayoutManager
        binding.rvMessage.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val lastVisiblePosition = layoutManager.findLastCompletelyVisibleItemPosition()
                binding.imgScrollToBottom.visibility =
                    if (RecyclerView.NO_POSITION == lastVisiblePosition || (lastVisiblePosition + 1) == binding.rvMessage.adapter?.itemCount) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
            }
        })
        binding.rvStatus.adapter = StatusAdapter(this)

        viewModel.initialize(this.application)

        lifecycleScope.launch(Dispatchers.Main) {
            viewModel.message.collect { msg ->
                appendMessageToUI(msg)
            }
        }
        lifecycleScope.launch(Dispatchers.Main) {
            viewModel.monitoringStatus.collect { statusMap ->
                for (keyAndValue in statusMap.entries) {
                    (binding.rvStatus.adapter as? StatusAdapter)?.updateStatus(
                        keyAndValue.key,
                        keyAndValue.value
                    )
                }
            }
        }
    }

    private fun appendMessageToUI(msg: Pair<Int, String>) {
        val needToScrollToEnd = binding.imgScrollToBottom.visibility != View.VISIBLE
        (binding.rvMessage.adapter as? MessageAdapter)?.appendMessage(msg.first, msg.second)
        if (needToScrollToEnd) {
            (binding.rvMessage.layoutManager as? LinearLayoutManager)?.scrollToPosition(
                binding.rvMessage.adapter!!.itemCount - 1
            )
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
                MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(
                    surfaceView.holder.surface
                )
            }
        }
    }

    override fun onDestroy() {
        releaseSurfaceView()

        super.onDestroy()
    }
}

fun Activity.showToast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}