package dji.sampleV5.aircraft.models

import android.Manifest
import android.app.Application
import android.os.SystemClock
import android.util.ArrayMap
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dji.sampleV5.aircraft.BuildConfig
import dji.sampleV5.aircraft.DJIApplication.Companion.idToString
import dji.sampleV5.aircraft.PING_INTERVAL
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.USE_DRONE_CAMERA
import dji.sampleV5.aircraft.USE_MOCK_CONTROL
import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.media.DronePhotoCapturer
import dji.sampleV5.aircraft.motiontracking.DjiMotionTracker
import dji.sampleV5.aircraft.utils.format
import dji.sampleV5.aircraft.utils.toData
import dji.sampleV5.aircraft.utils.toJson
import dji.sampleV5.aircraft.virtualcontroller.DroneStatusMonitor
import dji.sampleV5.aircraft.virtualcontroller.IDroneController
import dji.sampleV5.aircraft.virtualcontroller.MockDroneController
import dji.sampleV5.aircraft.virtualcontroller.VirtualDroneController
import dji.sampleV5.aircraft.webrtc.ConnectionInfo
import dji.sampleV5.aircraft.webrtc.DATA_RECEIVER
import dji.sampleV5.aircraft.webrtc.DJIVideoCapturer
import dji.sampleV5.aircraft.webrtc.DataFromChannel
import dji.sampleV5.aircraft.webrtc.EVENT_CREATE_CONNECTION_ERROR_FOR_PUBLICATION
import dji.sampleV5.aircraft.webrtc.EVENT_CREATE_CONNECTION_SUCCESS_FOR_PUBLICATION
import dji.sampleV5.aircraft.webrtc.EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION
import dji.sampleV5.aircraft.webrtc.EVENT_EXCHANGE_OFFER_SUCCESS_FOR_PUBLICATION
import dji.sampleV5.aircraft.webrtc.EVENT_HEADSET_OFFLINE
import dji.sampleV5.aircraft.webrtc.EVENT_HEADSET_ONLINE
import dji.sampleV5.aircraft.webrtc.EVENT_LOG_MESSAGE
import dji.sampleV5.aircraft.webrtc.EVENT_RECEIVED_DATA
import dji.sampleV5.aircraft.webrtc.VIDEO_PUBLISHER
import dji.sampleV5.aircraft.webrtc.WebRtcEvent
import dji.sampleV5.aircraft.webrtc.WebRtcManager
import dji.sdk.keyvalue.key.CameraKey
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.internal.closeQuietly
import org.opencv.core.Size
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


private val permissions = listOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_NETWORK_STATE
)

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

class CameraStreamVM : ViewModel(), Consumer<WebRtcEvent>, SimulatorStatusListener {

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

    private var motionTracker: DjiMotionTracker? = null

    private var droneController: IDroneController? = null

    private var videoResolution = 1920 to 1080

    private var videoFrameRate = 30

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
                val position = tracker.getCurrentPosition()
                val rotation = tracker.getCurrentRotation()
                val state = tracker.getTrackingState().toString()

                emitMonitorStatus(
                    mapOf(
                        R.string.hint_drone_current_position.idToString() to "${position[0].format()} / ${position[1].format()} / ${position[2].format()}",
                        R.string.hint_drone_attitude.idToString() to "${rotation[0].format()} / ${rotation[2].format()} / ${rotation[1].format()}",
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

        if (null == motionTracker) {
            // TODO initialize the tracker first, the target size need to be adjusted based on the real resolution of the video
            motionTracker = DjiMotionTracker(
                Size(640.0, 360.0),
                statusMonitor!!,
                Dispatchers.IO, viewModelScope
            )

            val configFile: File = File(application.filesDir, "pixel_6_mono.yaml")
            if (!configFile.exists()) {
                copyFileFromRaw("pixel_6_mono.yaml", configFile.absolutePath)
            }

            val vocabFile = File(application.filesDir, "orb_vocab.fbow")
            if (!vocabFile.exists()) {
                copyFileFromRaw("orb_vocab.fbow", vocabFile.absolutePath)
            }

            motionTracker?.initialize(configFile.absolutePath, vocabFile.absolutePath)
            motionTracker?.setLoopDetector(false)
        }
        motionTracker?.startup()
        motionTracker?.setMappingModule(true)

        photoCapturer.startup()

        isVideoPublish.postValue(true)
        isDroneControlling.postValue(false)

    }

    fun stopPublish() {
        webRtcManager.stop()

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

        isVideoPublish.postValue(false)
        isDroneControlling.postValue(false)

        showMessageOnLogAndScreen(Log.INFO, "Stop publishing video.")
    }

    fun getReadyForRemoteControl() {
        // direct the drone to fly to an initial position
        Timber.d("User click 'prepare for remote control'")

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
                    motionTracker!!,
                    statusMonitor!!,
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
                controlData.currentPosition.z += 0.06f
                controlData.currentRotation.x += 45
            }

            R.id.btn_backward -> { // backward
                showMessageOnLogAndScreen(Log.DEBUG, "Press backward")
                controlData.currentPosition.z -= 0.06f
                controlData.currentRotation.x -= 45
            }

            R.id.btn_left -> { // left
                showMessageOnLogAndScreen(Log.DEBUG, "Press left")
                controlData.currentPosition.x -= 0.06f
                controlData.currentRotation.z += 45
            }

            R.id.btn_right -> { // right
                showMessageOnLogAndScreen(Log.DEBUG, "Press right")
                controlData.currentPosition.x += 0.06f
                controlData.currentRotation.z -= 45
            }

            R.id.btn_rotate_left -> {
                showMessageOnLogAndScreen(Log.DEBUG, "Press rotate to left")
            }

            R.id.btn_rotate_right -> {
                showMessageOnLogAndScreen(Log.DEBUG, "Press rotate to right")
            }

            R.id.btn_rise_gimbal -> {
                showMessageOnLogAndScreen(Log.DEBUG, "Rise the gimbal")
                droneController?.riseAndSetGimbal(10.0)
            }

            R.id.btn_set_gimbal -> {
                showMessageOnLogAndScreen(Log.DEBUG, "Set the gimbal")
                droneController?.riseAndSetGimbal(-10.0)
            }

            else -> {
                // reset
                showMessageOnLogAndScreen(Log.DEBUG, "Press reset")
            }
        }
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

    override fun accept(event: WebRtcEvent) {
        eventHandles[event.event]?.invoke(event)
    }

    override fun onCleared() {
        super.onCleared()
        controllerStatusHandleScheduler.closeQuietly()
        eventDisposable.dispose()

        webRtcManager.stop()

        statusMonitor?.stopMonitoring()

        viewModelScope.launch(Dispatchers.Main) {
            droneController?.destroy()
        }

        if (BuildConfig.DEBUG) {
            SimulatorManager.getInstance().removeSimulatorStateListener(this)
        }
    }

    private fun onReceivedData(data: DataFromChannel) {
        if (DATA_RECEIVER == data.identity) {
            val rootMessage = gson.fromJson(data.data, RootMessage::class.java)
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
            KeyTools.createKey(CameraKey.KeyCameraFocusRingValue).set(it, {
                showMessageOnLogAndScreen(
                    Log.INFO,
                    "Set the maximum camera focus ring value to $it"
                )
            }, {
                showMessageOnLogAndScreen(
                    Log.ERROR,
                    "Fail to set the maximum camera focus ring value"
                )
            })
        }, {
            showMessageOnLogAndScreen(Log.ERROR, "Fail to get the maximum camera focus ring value")
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