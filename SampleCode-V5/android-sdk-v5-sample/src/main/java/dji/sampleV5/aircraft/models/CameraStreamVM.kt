package dji.sampleV5.aircraft.models

import android.app.Application
import android.os.SystemClock
import android.util.ArrayMap
import android.util.Log
import android.util.Range
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dji.sampleV5.aircraft.BuildConfig
import dji.sampleV5.aircraft.DJIApplication.Companion.idToString
import dji.sampleV5.aircraft.PING_INTERVAL
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.TARGET_FOCUS_RING_VALUE
import dji.sampleV5.aircraft.TARGET_VIDEO_FRAME_SIZE
import dji.sampleV5.aircraft.USE_DRONE_CAMERA
import dji.sampleV5.aircraft.USE_MOCK_CONTROL
import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.media.DronePhotoCapturer
import dji.sampleV5.aircraft.motiontracking.DjiVSLamTracker
import dji.sampleV5.aircraft.motiontracking.ObjectPose
import dji.sampleV5.aircraft.motiontracking.RemotePoseTracker
import dji.sampleV5.aircraft.utils.format
import dji.sampleV5.aircraft.utils.toData
import dji.sampleV5.aircraft.utils.toJson
import dji.sampleV5.aircraft.virtualcontroller.DjiDrone
import dji.sampleV5.aircraft.virtualcontroller.DroneStatusMonitor
import dji.sampleV5.aircraft.virtualcontroller.IDroneController
import dji.sampleV5.aircraft.virtualcontroller.MockDroneController
import dji.sampleV5.aircraft.virtualcontroller.OnRawDataObserver
import dji.sampleV5.aircraft.virtualcontroller.VirtualDroneController
import dji.sampleV5.aircraft.virtualcontroller.adjustCameraOrientation
import dji.sampleV5.aircraft.webrtc.ConnectionInfo
import dji.sampleV5.aircraft.webrtc.DATA_RECEIVER
import dji.sampleV5.aircraft.webrtc.DJIVideoCapturer
import dji.sampleV5.aircraft.webrtc.DataFromChannel
import dji.sampleV5.aircraft.webrtc.EVENT_CREATE_CONNECTION_ERROR_FOR_PUBLICATION
import dji.sampleV5.aircraft.webrtc.EVENT_CREATE_CONNECTION_SUCCESS_FOR_PUBLICATION
import dji.sampleV5.aircraft.webrtc.EVENT_DRONE_TRACKER_OFFLINE
import dji.sampleV5.aircraft.webrtc.EVENT_DRONE_TRACKER_ONLINE
import dji.sampleV5.aircraft.webrtc.EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION
import dji.sampleV5.aircraft.webrtc.EVENT_EXCHANGE_OFFER_SUCCESS_FOR_PUBLICATION
import dji.sampleV5.aircraft.webrtc.EVENT_HEADSET_OFFLINE
import dji.sampleV5.aircraft.webrtc.EVENT_HEADSET_ONLINE
import dji.sampleV5.aircraft.webrtc.EVENT_LOG_MESSAGE
import dji.sampleV5.aircraft.webrtc.EVENT_RECEIVED_DATA
import dji.sampleV5.aircraft.webrtc.POSITION_RECEIVER
import dji.sampleV5.aircraft.webrtc.VIDEO_PUBLISHER
import dji.sampleV5.aircraft.webrtc.WebRtcEvent
import dji.sampleV5.aircraft.webrtc.WebRtcManager
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraFocusMode
import dji.sdk.keyvalue.value.camera.VideoFrameRate
import dji.sdk.keyvalue.value.camera.VideoResolution
import dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.simulator.SimulatorState
import dji.v5.manager.aircraft.simulator.SimulatorStatusListener
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.internal.closeQuietly
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors


data class VideoTrackAdded(
    val eglBase: EglBase,
    val videoTrack: VideoTrack,
    val videoCapturer: VideoCapturer,
    val useDroneCamera: Boolean,
)

data class RootMessage(
    val data: String,
    val channel: String,
    val type: String,
    val from: String,
)


data class ControlStatusData(
    var benchmarkPosition: Vector3D = Vector3D(),
    var benchmarkRotation: Vector3D = Vector3D(),
    var lastPosition: Vector3D = Vector3D(),
    var lastRotation: Vector3D = Vector3D(),
    var currentPosition: Vector3D = Vector3D(),
    var currentRotation: Vector3D = Vector3D(),

    var sampleTimestamp: Long = SystemClock.elapsedRealtime(),
    var benchmarkSampleTimestamp: Long = SystemClock.elapsedRealtime()
)

class CameraStreamVM : ViewModel(), Consumer<WebRtcEvent>, SimulatorStatusListener,
    OnRawDataObserver {

    private lateinit var webRtcManager: WebRtcManager
    private lateinit var eventDisposable: Disposable
    private lateinit var application: Application

    val requestPermissions = MutableLiveData<List<String>>()
    val videoTrackUpdate = MutableLiveData<VideoTrackAdded>()

    val message = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = Int.MAX_VALUE)

    val isVideoPublish = MutableLiveData(false)
    val isDroneControlling = MutableLiveData(false)

    val monitoringStatus =
        MutableSharedFlow<Map<String, String>>(extraBufferCapacity = Int.MAX_VALUE)

    private var videoCapturer: VideoCapturer? = null

    private lateinit var photoCapturer: DronePhotoCapturer

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    private val gson = Gson()

    private var lastDataLatencyTime: Long = 0

    private var eventHandles: ArrayMap<String, (WebRtcEvent) -> Unit> = ArrayMap()

    private var statusMonitor: DroneStatusMonitor? = null

    private var motionTracker: DjiVSLamTracker? = null

    private var remotePoseTracker: RemotePoseTracker? = null

    private var droneController: IDroneController? = null

    private var videoResolution = 1920 to 1080

    private var videoFrameRate = 30

    private var demoPathJob: Job? = null

    var focusRingValue = MutableLiveData<Int>(1)
    var focusRingRange = MutableLiveData<Range<Int>>(Range(0, 100))

    private val controllerStatusHandleScheduler =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    fun initialize(application: Application) {
        this.application = application
        webRtcManager = WebRtcManager(scope = viewModelScope, application)
        eventDisposable = webRtcManager.webRtcEventObservable.subscribe(this)

        val dateTimeFormat = SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
        photoCapturer = DronePhotoCapturer(
            viewModelScope,
            Dispatchers.IO,
            "drone_camera_calibration_${dateTimeFormat}_",
            application.getExternalFilesDir(null)!!
        )

        isVideoPublish.value = false
        isDroneControlling.value = false

        initializeEventHandles()

        var isDroneReady = false
        statusMonitor = DroneStatusMonitor(viewModelScope, this::showMessageOnLogAndScreen) {
            emitMonitorStatus(it)

            droneController?.let { controller ->
                if (controller.isDroneReady() != isDroneReady) {
                    // log only when value changes
                    Timber.d("If the drone is ready: ${controller.isDroneReady()}")
                    isDroneReady = controller.isDroneReady()

                    isDroneControlling.postValue(isDroneReady)
                }
                emitMonitorStatus(
                    mapOf(
                        R.string.hint_remote_control.idToString() to if (controller.isDroneReady()) {
                            "Ready"
                        } else {
                            "Not Ready"
                        }
                    )
                )
            }

            motionTracker?.let { tracker ->
                val position = tracker.getPosition()
                val rotation = tracker.getRotation()
                val state = tracker.getTrackingState().toString()

                emitMonitorStatus(
                    mapOf(
                        R.string.hint_drone_current_position.idToString() to "${position.x.format()} / ${position.y.format()} / ${position.z.format()}",
                        R.string.hint_drone_attitude.idToString() to "${rotation.y.format()} / ${rotation.z.format()} / ${rotation.x.format()}",
                        R.string.hint_drone_tracking_state.idToString() to state
                    )
                )
            } ?: run {
                emitMonitorStatus(
                    mapOf(
                        R.string.hint_drone_current_position.idToString() to "-/-/-",
                        R.string.hint_drone_attitude.idToString() to "-/-/-",
                        R.string.hint_drone_tracking_state.idToString() to "-"
                    )
                )
            }
        }
        statusMonitor?.startMonitoring()

        if (BuildConfig.DEBUG) {
            SimulatorManager.getInstance().addSimulatorStateListener(this)

            val result =
                R.string.hint_is_in_simulator_mode.idToString() to if (SimulatorManager.getInstance().isSimulatorEnabled) {
                    "Yes"
                } else {
                    "No"
                }
            emitMonitorStatus(mapOf(result))
        }
        initializeDroneParameters()
    }

    fun clickPublishBtn() {
        startPublish()
    }


    fun startPublish() {
        webRtcManager.start()

        // make sure the camera focus ring value is stable at 49
        statusMonitor?.register(DJICameraKey.KeyCameraFocusRingValue, this)

        if (null == motionTracker) {
            // initialize the tracker first, the target size need to be adjusted based on the real resolution of the video
            motionTracker = DjiVSLamTracker(
                TARGET_VIDEO_FRAME_SIZE,
                statusMonitor!!,
                Dispatchers.IO, viewModelScope
            )

            val configFile: File = File(application.filesDir, "drone_mono.yaml")
            if (configFile.exists()) {
                configFile.delete()
            }
            copyFileFromRaw("drone_mono.yaml", configFile.absolutePath)

            val vocabFile = File(application.filesDir, "orb_vocab.fbow")
            if (!vocabFile.exists()) {
                copyFileFromRaw("orb_vocab.fbow", vocabFile.absolutePath)
            }

            motionTracker?.initialize(configFile.absolutePath, vocabFile.absolutePath)
            motionTracker?.setLoopDetector(true)
        }
        motionTracker?.startup()
        motionTracker?.setMappingModule(true)

        if (null == remotePoseTracker) {
            remotePoseTracker = RemotePoseTracker(statusMonitor!!, Dispatchers.IO, viewModelScope)
        }
        remotePoseTracker?.startup()

        photoCapturer.startup()

        isVideoPublish.postValue(true)
        isDroneControlling.postValue(false)

        statusMonitor?.register(DJICameraKey.KeyCameraFocusRingValue, this)
    }

    fun stopPublish() {
        statusMonitor?.unregister(DJICameraKey.KeyCameraFocusRingValue, this)

        webRtcManager.stop()

        motionTracker?.shutdown()
        motionTracker?.destroy()
        motionTracker = null

        remotePoseTracker?.shutdown()
        remotePoseTracker = null

        audioSource?.dispose()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()

        audioSource = null
        videoCapturer = null
        videoSource = null

        photoCapturer.stop()

        isVideoPublish.postValue(false)
        isDroneControlling.postValue(false)

        showMessageOnLogAndScreen(Log.INFO, "Stop publishing video.")
    }

    fun getReadyForRemoteControl() {
        // direct the drone to fly to an initial position
        Timber.d("User click 'prepare for remote control'")

        // TODO reset temporary variables
        temporaryAngle = 0
        temporaryGimbalAngle = 0

        if (null == droneController) {
            droneController = if (USE_MOCK_CONTROL)
                MockDroneController(
                    viewModelScope,
                    this::controlStatusFeedback,
                    this::showMessageOnLogAndScreen
                )
            else
                VirtualDroneController(
                    viewModelScope,
                    this::controlStatusFeedback,
                    // TODO replace the trackers with different strategies for test
//                    motionTracker!!,
                    remotePoseTracker!!,
                    DjiDrone(viewModelScope, Dispatchers.IO, statusMonitor!!, this::controlStatusFeedback, this::showMessageOnLogAndScreen),
                    this::showMessageOnLogAndScreen
                )

            try {
                viewModelScope.launch(Dispatchers.Main) {
                    droneController?.prepareDrone(0)
                    isDroneControlling.postValue(true)
                }
            } catch (e: Exception) {
                droneController = null
                showMessageOnLogAndScreen(Log.ERROR, e.message ?: "", e)
            }
        }
    }

    fun abortDroneControl() {
        if (null == droneController) return
        Timber.d("User click 'abort drone control'")

        viewModelScope.launch(Dispatchers.Main) {
            droneController?.abort()
            isDroneControlling.postValue(false)
            droneController = null
        }
    }

    fun landOffDrone() {
        viewModelScope.launch(Dispatchers.Main) {
            droneController?.landOff()
        }
    }


    private var temporaryAngle = 0
    private var temporaryGimbalAngle = 0

    fun flightToDirection(direction: Int) {
        // comment this check for debugging
//        if (droneController?.isDroneReady() != true) {
//            return
//        }
        // DEBUG create initial position

        val controlData = ControlStatusData()
        when (direction) {
            R.id.btn_forward -> { // forward // North
                showMessageOnLogAndScreen(Log.DEBUG, "Press forward")
                controlData.currentPosition.y = -0.2f
            }

            R.id.btn_backward -> { // backward
                showMessageOnLogAndScreen(Log.DEBUG, "Press backward")
                controlData.currentPosition.y = 0.2f
            }

            R.id.btn_left -> { // left
                showMessageOnLogAndScreen(Log.DEBUG, "Press left")
                controlData.currentPosition.x -= 0.2f
            }

            R.id.btn_right -> { // right
                showMessageOnLogAndScreen(Log.DEBUG, "Press right")
                controlData.currentPosition.x += 0.2f
            }

            R.id.btn_rotate_left -> {
                showMessageOnLogAndScreen(Log.DEBUG, "Press rotate to left")
                temporaryAngle -= 5
            }

            R.id.btn_rotate_right -> {
                showMessageOnLogAndScreen(Log.DEBUG, "Press rotate to right")
                temporaryAngle += 5
            }

            R.id.btn_rise_gimbal -> {
                showMessageOnLogAndScreen(Log.DEBUG, "Rise the gimbal")
                temporaryGimbalAngle += 10
            }

            R.id.btn_set_gimbal -> {
                showMessageOnLogAndScreen(Log.DEBUG, "Set the gimbal")
                temporaryGimbalAngle -= 10
            }

            R.id.btn_demo_flight_path -> {
                viewModelScope.launch(Dispatchers.Main) {
                    demoPathJob?.apply {
                        if (isActive) {
                            cancelAndJoin()
                        }
                    }
                    executeDemoPath()
                }
                return
            }

            else -> {
                temporaryAngle = 0
                temporaryGimbalAngle = 0
                // reset
                showMessageOnLogAndScreen(Log.DEBUG, "Press reset")
            }
        }
        controlData.currentRotation.x = temporaryGimbalAngle.toFloat()
        controlData.currentRotation.y = temporaryAngle.toFloat()
        viewModelScope.launch(Dispatchers.Main) {
            droneController?.onControllerStatusData(controlData)
        }
    }

    fun takePhoto() {
        viewModelScope.launch(Dispatchers.IO) {
            val targetFile = photoCapturer.capture()
            if (null != targetFile) {
                showMessageOnLogAndScreen(Log.INFO, "Take a photo: $targetFile")
            } else {
                showMessageOnLogAndScreen(Log.ERROR, "Fail to take a photo")
            }
        }
    }

    fun executeDemoPath() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pathList = application.assets.open("flight_path.json").use { inputStream ->
                    val size = inputStream.available()
                    val buffer = ByteArray(size)
                    inputStream.read(buffer)
                    val jsonString = String(buffer, Charsets.UTF_8)

                    val listType = object : TypeToken<List<ControlStatusData>>() {}.type
                    val tmp: List<ControlStatusData> = gson.fromJson(jsonString, listType)

                    showMessageOnLogAndScreen(Log.INFO, "Loaded demo path with ${tmp.size} points")

                    tmp
                    // You can add logic here to feed the pathList to the droneController
                }

                if (pathList.isEmpty()) return@launch

                for (item in pathList) {
                    delay(100)

                    viewModelScope.launch(Dispatchers.Main) {
                        droneController?.onControllerStatusData(item)
                    }
                }
            } catch (e: Exception) {
                showMessageOnLogAndScreen(Log.ERROR, "Failed to load demo path", e)
            }
        }.also { demoPathJob = it }
    }

    override fun invoke(p1: DJIKeyInfo<*>, p2: Any?) {
        if (p1.innerIdentifier == DJICameraKey.KeyCameraFocusRingValue.innerIdentifier && p2 != TARGET_FOCUS_RING_VALUE) {
            Timber.d("Reset the focus ring value to $TARGET_FOCUS_RING_VALUE")
            showMessageOnLogAndScreen(
                Log.ERROR, "Drone camera focus ring value changes: $p2, reset" +
                        " it to $TARGET_FOCUS_RING_VALUE", null
            )
            KeyTools.createKey(DJICameraKey.KeyCameraFocusRingValue).set(TARGET_FOCUS_RING_VALUE)
        }
    }

    fun updateFocusRing(float: Float) {
        KeyTools.createKey(CameraKey.KeyCameraFocusRingValue).set(float.toInt(), {}, {})
    }

    override fun accept(event: WebRtcEvent) {
        eventHandles[event.event]?.invoke(event)
    }

    override fun onCleared() {
        super.onCleared()
        controllerStatusHandleScheduler.closeQuietly()
        eventDisposable.dispose()

        webRtcManager.destroy()

        statusMonitor?.stopMonitoring()

        motionTracker?.shutdown()
        motionTracker?.destroy()
        motionTracker = null

        audioSource?.dispose()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()

        audioSource = null
        videoCapturer = null
        videoSource = null

        photoCapturer.stop()

        viewModelScope.launch(Dispatchers.Main) {
            droneController?.destroy()
        }

        if (BuildConfig.DEBUG) {
            SimulatorManager.getInstance().removeSimulatorStateListener(this)
        }
    }

    private fun onReceivedData(data: DataFromChannel) {
        val rootMessage = gson.fromJson(data.data, RootMessage::class.java)
        if (DATA_RECEIVER == data.identity) {
            if ("Ping".equals(rootMessage?.type, true)) {
                webRtcManager.sendData(rootMessage.data, "Pong")
            } else if ("Pong".equals(rootMessage?.type, true)) {
                // check the data latency
                viewModelScope.launch {
                    val msgTime = rootMessage.data.toLong()
                    if (msgTime >= lastDataLatencyTime) {
                        lastDataLatencyTime = msgTime
                        // calculate data latency
                        val result =
                            R.string.hint_data_latency.idToString() to ((System.currentTimeMillis() - lastDataLatencyTime) / 2).toString()
                        emitMonitorStatus(mapOf(result))
                        Timber.i("${result.first} --> ${result.second}}")
                    }
                }
            } else if ("ControlStatus".equals(rootMessage?.type, true)) {
                // TODO Received status update from headset/controller, how to handle this data
                //  Be careful, this method is called in the UI thread
                val statusData = rootMessage.data.toData(ControlStatusData::class.java)
                // redirect to UI thread to handle the data
                viewModelScope.launch(controllerStatusHandleScheduler) {
                    Timber.d("onControllerStatusData: ${statusData.toJson()}")
                    droneController?.onControllerStatusData(statusData)
                }
            }
        } else if (POSITION_RECEIVER == data.identity) {
            if ("Pose".equals(rootMessage?.type, true)) {
                // in theory, position tracker only support one form of data
                val objectPose = gson.fromJson(rootMessage.data, ObjectPose::class.java)
                objectPose.localTimestamp = SystemClock.elapsedRealtime()
                // no matter what status this tracker is,
                // just pass the data to it and let it decide how to deal with this data
                remotePoseTracker?.feedFrame(objectPose)
            } else if ("Ping".equals(rootMessage?.type, true)) {
                // ignore this type of message
            }
        }
    }


    private fun initializeEventHandles() {
        eventHandles[EVENT_CREATE_CONNECTION_SUCCESS_FOR_PUBLICATION] = {
            val connectionInfo = it.data as? ConnectionInfo
            if (VIDEO_PUBLISHER == connectionInfo?.identity) {
                Timber.i("Attach video and audio info to peer connection")
                attachVideoAndAudioToConnection(connectionInfo)
            }
        }
        eventHandles[EVENT_CREATE_CONNECTION_ERROR_FOR_PUBLICATION] = {
            // create connection error, the data is null
            showMessageOnLogAndScreen(
                Log.ERROR,
                "Failed to create a connection for video publication"
            )

            stopPublish()
        }
        eventHandles[EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION] = {
            val msg: String
            val exception: Exception?
            if (it.data is Exception) {
                msg = "Got an error while exchanging the offer with server"
                exception = it.data
            } else {
                // string
                msg = "Got an error while exchanging the offer with server: ${it.data}"
                exception = null
            }
            showMessageOnLogAndScreen(Log.ERROR, msg, exception)

            webRtcManager.stop()
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null
        }
        eventHandles[EVENT_RECEIVED_DATA] = {
            val data = it.data as? DataFromChannel
            data?.let {
                onReceivedData(data)
            }
        }
        eventHandles[EVENT_EXCHANGE_OFFER_SUCCESS_FOR_PUBLICATION] = {
            // exchange offer successfully, start periodic task to send ping to the headset
            if (it.data == VIDEO_PUBLISHER) {
                startPeriodicTask()

                showMessageOnLogAndScreen(Log.INFO, "Start publishing video.")
            }
        }
        eventHandles[EVENT_HEADSET_ONLINE] = {
            // headset is online now
            showMessageOnLogAndScreen(Log.INFO, "The headset is online now.")
        }
        eventHandles[EVENT_HEADSET_OFFLINE] = {
            // headset is offline now
            showMessageOnLogAndScreen(Log.INFO, "The headset is offline now.")

        }
        eventHandles[EVENT_DRONE_TRACKER_ONLINE] = {
            showMessageOnLogAndScreen(Log.INFO, "Drone pose tracker is online now.")
        }
        eventHandles[EVENT_DRONE_TRACKER_OFFLINE] = {
            showMessageOnLogAndScreen(Log.INFO, "Drone pose tracker is offline now.")
        }
        eventHandles[EVENT_LOG_MESSAGE] = {
            (it.data as? Pair<*, *>)?.let { data ->
                if (data.first is Int && data.second is String) {
                    showMessageOnLogAndScreen(data.first as Int, data.second as String)
                }
            }
        }
    }

    private fun startPeriodicTask() {
        viewModelScope.launch {
            while (null != videoSource && isActive) {
                delay(PING_INTERVAL)

                webRtcManager.sendData("${System.currentTimeMillis()}", "Ping")

                // obtain the push video frame rate
                val result = webRtcManager.obtainStatisticsInformation()
                result?.statsMap?.forEach {
                    if ("outbound-rtp" == it.value.type && "video".equals(
                            it.value.members["kind"]?.toString(),
                            true
                        )
                    ) {
                        it.value.members["framesPerSecond"]?.let { fps ->
                            val result = R.string.hint_push_video.idToString() to fps.toString()
                            emitMonitorStatus(mapOf(result))
                            Timber.i("${result.first} --> ${result.second}}")
                        }
                    }
                }

                (videoCapturer as? DJIVideoCapturer)?.let {
                    val result =
                        R.string.hint_fetch_video.idToString() to it.fetchFrameRate().toString()
                    emitMonitorStatus(mapOf(result))
                    Timber.i("${result.first} --> ${result.second}}")
                }

                remotePoseTracker?.let { tracker ->
                    val pose = tracker.getPose()
                    val position = pose[0]
                    val rotation = pose[1]
                    val state = tracker.getTrackingState().toString()

                    emitMonitorStatus(
                        mapOf(
                            R.string.hint_drone_current_position_marker.idToString() to "${position.x.format()} / ${position.y.format()} / ${position.z.format()}",
                            R.string.hint_drone_current_rotation_marker.idToString() to "${rotation.y.format()} / ${rotation.x.format()} / ${rotation.z.format()}",
                            R.string.hint_drone_tracking_state_marker.idToString() to state
                        )
                    )
                } ?: run {
                    emitMonitorStatus(
                        mapOf(
                            R.string.hint_drone_current_position_marker.idToString() to "-/-/-",
                            R.string.hint_drone_current_rotation_marker.idToString() to "-/-/-",
                            R.string.hint_drone_tracking_state_marker.idToString() to "-"
                        )
                    )
                }
            }
        }
    }

    private fun attachVideoAndAudioToConnection(connectionInfo: ConnectionInfo) {
        videoCapturer = if (USE_DRONE_CAMERA) DJIVideoCapturer(scope = viewModelScope) else {
            val videoCapturer = createCameraCapturer(Camera2Enumerator(application))
            if (null == videoCapturer) {
                Timber.e("unable to create the video capturer!")
                return
            }
            videoCapturer
        }
        videoSource =
            connectionInfo.connectionFactory.createVideoSource(videoCapturer!!.isScreencast)

        // Create AudioSource with constraints
        val mediaConstraints = MediaConstraints()
        mediaConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "googEchoCancellation",
                "true"
            )
        )
        mediaConstraints.mandatory.add(
            MediaConstraints.KeyValuePair(
                "googNoiseSuppression",
                "true"
            )
        )
        audioSource = connectionInfo.connectionFactory.createAudioSource(mediaConstraints)
        val audioTrack =
            connectionInfo.connectionFactory.createAudioTrack("ARDAMSa0", audioSource)

        connectionInfo.connection.addTrack(audioTrack, listOf("audioId"))

        videoCapturer!!.initialize(
            SurfaceTextureHelper.create("CaptureThread", connectionInfo.eglBase.eglBaseContext),
            application, videoSource!!.capturerObserver
        )
        videoCapturer!!.startCapture(videoResolution.first, videoResolution.second, videoFrameRate)

        val localVideoTrack =
            connectionInfo.connectionFactory.createVideoTrack("videoTrack", videoSource)
        videoTrackUpdate.postValue(
            VideoTrackAdded(
                connectionInfo.eglBase,
                localVideoTrack,
                videoCapturer!!,
                USE_DRONE_CAMERA
            )
        )
        val sender = connectionInfo.connection.addTrack(localVideoTrack, listOf("streamId"))
        val parameters = sender.parameters
        for (parameter in parameters.encodings) {
            parameter.minBitrateBps = 4500000
            parameter.maxBitrateBps = 6000000
            parameter.maxFramerate = videoFrameRate
        }
        sender.parameters = parameters
    }

    private fun emitMonitorStatus(keyAndValue: Map<String, String>) {
        viewModelScope.launch(Dispatchers.Main) {
            monitoringStatus.emit(keyAndValue)
        }
    }

    private fun createCameraCapturer(enumerator: Camera2Enumerator): CameraVideoCapturer? {
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                val capturer = enumerator.createCapturer(deviceName, null)
                if (null != capturer) {
                    return capturer
                }
            }
        }
        return null
    }

    private fun initializeDroneParameters() {
        // INFO change the resolution and frame rate of the video, seems like it won't affect the video for streaming
        val config = VideoResolutionFrameRate(
            VideoResolution.RESOLUTION_1080X1920P,
            VideoFrameRate.RATE_PRECISE_30FPS
        )
        KeyTools.createKey(CameraKey.KeyVideoResolutionFrameRate).set(config, {
            showMessageOnLogAndScreen(
                Log.INFO,
                "Change video resolution to 1080x1920 and frame rate to 30"
            )
            videoResolution = 1080 to 1920
            videoFrameRate = 30
        }, { error ->
            showMessageOnLogAndScreen(
                Log.ERROR,
                "Fail to change the video resolution and frame rate ${error.errorCode()}: ${error.hint()}"
            )
        })

        // TODO change the camera focus length and test if it will affect the streaming video
        KeyTools.createKey(CameraKey.KeyCameraFocusMode).set(CameraFocusMode.MANUAL, {
            showMessageOnLogAndScreen(Log.INFO, "Change camera focus mode to manual")
        }, {
            showMessageOnLogAndScreen(Log.ERROR, "Fail to change the camera focus mode to manual")
        })

        // TODO set the camera focus ring value
        KeyTools.createKey(CameraKey.KeyCameraFocusRingMaxValue).get({
            focusRingRange.postValue(
                Range(
                    focusRingRange.value!!.lower,
                    it!!
                )
            )
            showMessageOnLogAndScreen(Log.INFO, "Set the maximum camera focus ring value to 100")
        }, {
            showMessageOnLogAndScreen(Log.ERROR, "Fail to get the maximum camera focus ring value")
        })
        KeyTools.createKey(CameraKey.KeyCameraFocusRingMinValue).get({
            focusRingRange.postValue(
                Range(
                    it!!,
                    focusRingRange.value!!.upper
                )
            )
            showMessageOnLogAndScreen(Log.INFO, "Set the minimum camera focus ring value to 0")
        }, {
            showMessageOnLogAndScreen(Log.ERROR, "Fail to get the minimum camera focus ring value")
        })
        focusRingValue.postValue(TARGET_FOCUS_RING_VALUE)
        KeyTools.createKey(CameraKey.KeyCameraFocusRingValue).set(TARGET_FOCUS_RING_VALUE, {
            showMessageOnLogAndScreen(
                Log.INFO,
                "Set the maximum camera focus ring value to 49"
            )
        }, {
            showMessageOnLogAndScreen(
                Log.ERROR,
                "Fail to set the maximum camera focus ring value"
            )
        })
    }

    private fun controlStatusFeedback(status: String, data: String) {
        webRtcManager.sendData(data, status)
    }

    private fun showMessageOnLogAndScreen(level: Int, msg: String, exception: Throwable? = null) {
        Timber.log(level, exception, msg)

        viewModelScope.launch {
            message.emit(level to msg)
        }
    }


    private fun copyFileFromRaw(srcFileName: String, destPath: String) {
        var fs: InputStream? = null
        var outFs: OutputStream? = null
        try {
            fs = application.assets.open(srcFileName)
            outFs = File(destPath).outputStream()
            fs.copyTo(outFs)
        } finally {
            fs?.closeQuietly()
            outFs?.closeQuietly()
        }
    }

    override fun onUpdate(state: SimulatorState) {
        val roll = state.roll.format()
        val yaw = state.yaw.format()
        val pitch = state.pitch.format()
        val x = state.positionX.format()
        val y = state.positionY.format()
        val z = state.positionZ.format()
        val simulatorState =
            "Flying-> ${state.isFlying}\tMotorsOn->${state.areMotorsOn()}\nRYP->$roll/$yaw/$pitch\nPosition(XYZ)->$x/$y/$z"
        // don't log the simulator state into logcat or file, because it is very frequent while in the simulator mode (everything is too ideal)
//        Timber.d("Simulator mode status: $simulatorState")
        emitMonitorStatus(mapOf(R.string.hint_simulator_state.idToString() to simulatorState))
    }
}