package dji.sampleV5.aircraft.models

import android.Manifest
import android.app.Application
import android.util.ArrayMap
import android.util.Log
import androidx.core.content.PermissionChecker
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dji.sampleV5.aircraft.DJIVideoCapturer
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.webrtc.ConnectionInfo
import dji.sampleV5.aircraft.webrtc.DATA_RECEIVER
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
import dji.sdk.keyvalue.key.ProductKey
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack


private const val TAG = "CameraStreamVM"

const val USE_DRONE_CAMERA = true

private const val PING_INTERVAL = 2000L

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

class CameraStreamVM : ViewModel(), Consumer<WebRtcEvent> {

    private lateinit var webRtcManager: WebRtcManager
    private lateinit var eventDisposable: Disposable
    private lateinit var application: Application

    val requestPermissions = MutableLiveData<List<String>>()
    val videoTrackUpdate = MutableLiveData<VideoTrackAdded>()

    val message = MutableSharedFlow<Pair<Int, String>>(extraBufferCapacity = Int.MAX_VALUE)

    val getReadyStatus = MutableLiveData<Boolean>()
    val publishBtnStatus = MutableLiveData<Boolean>()
    val stopBtnStatus = MutableLiveData<Boolean>()

    val monitoringStatus = MutableSharedFlow<Pair<Int, Any?>>(extraBufferCapacity = Int.MAX_VALUE)

    private var videoCapturer: VideoCapturer? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    private var tmpPermission = listOf<String>()

    private val gson = Gson()

    private var lastDataLatencyTime: Long = 0

    private var eventHandles: ArrayMap<String, (WebRtcEvent) -> Unit> = ArrayMap()

    fun initialize(application: Application) {
        this.application = application
        webRtcManager = WebRtcManager(scope = viewModelScope, application)
        eventDisposable = webRtcManager.webRtcEventObservable.subscribe(this)

        getReadyStatus.value = false
        stopBtnStatus.value = false
        publishBtnStatus.value = true

        initializeEventHandles()
        initialDroneControlling()
    }

    fun clickPublishBtn() {
        tmpPermission = getRequiredPermissions()
        if (tmpPermission.isEmpty()) {
            startPublish()
        } else {
            this.requestPermissions.postValue(tmpPermission)
        }
    }

    fun onRequestPermission(permissions: List<String>) {
        if (getRequiredPermissions().isEmpty()) {
            startPublish()
        } else {
            showMessageOnLogAndScreen(Log.ERROR, "Have not enough permissions")
        }
    }

    fun startPublish() {
        webRtcManager.start()

        publishBtnStatus.postValue(false)
        stopBtnStatus.postValue(true)
    }

    fun stopPublish() {
        webRtcManager.stop()

        audioSource?.dispose()
        videoCapturer?.dispose()
        videoSource?.dispose()

        audioSource = null
        videoCapturer = null
        videoSource = null

        getReadyStatus.postValue(false)
        publishBtnStatus.postValue(true)
        stopBtnStatus.postValue(false)

        showMessageOnLogAndScreen(Log.INFO, "Stop publishing video.")
    }

    fun getReadyForRemoteControl() {
        if (true != getReadyStatus.value) {
            showMessageOnLogAndScreen(Log.ERROR, "The headset is not online yet")
            return
        }

        // direct the drone to fly to an initial position
    }

    override fun accept(event: WebRtcEvent) {
        eventHandles.get(event.event)?.invoke(event)
    }

    override fun onCleared() {
        super.onCleared()
        eventDisposable.dispose()

        webRtcManager.stop()

        KeyManager.getInstance().cancelListen(this)
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
                        monitoringStatus.emit(R.string.hint_data_latency to (System.currentTimeMillis() - lastDataLatencyTime) / 2)
                    }
                }
            }
        }
    }

    private fun initialDroneControlling() {
        ProductKey.KeyConnection.create().listen(this, getOnce = false) {
            val isConnected = true == it
            showMessageOnLogAndScreen(if (isConnected) Log.INFO else Log.ERROR, "The drone is ${if (isConnected) "connected" else "disconnected"}.")
        }
    }

    private fun initializeEventHandles() {
        eventHandles[EVENT_CREATE_CONNECTION_SUCCESS_FOR_PUBLICATION] = {
            val connectionInfo = it.data as? ConnectionInfo
            if (VIDEO_PUBLISHER == connectionInfo?.identity) {
                Log.i(TAG, "Attach video and audio info to peer connection")
                attachVideoAndAudioToConnection(connectionInfo)
            }
        }
        eventHandles[EVENT_CREATE_CONNECTION_ERROR_FOR_PUBLICATION] = {
            // create connection error, the data is null
            showMessageOnLogAndScreen(Log.ERROR, "Failed to create a connection for video publication")

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
            (it.data as? Pair<Int, String>)?.let { data->
                showMessageOnLogAndScreen(data.first, data.second)
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
                    if ("outbound-rtp" == it.value.type && "video".equals(it.value.members["kind"]?.toString(), true)) {
                        it.value.members["framesPerSecond"]?.let { fps-> monitoringStatus.emit(R.string.hint_push_video to fps) }
                    }
                }
            }
        }
    }

    private fun attachVideoAndAudioToConnection(connectionInfo: ConnectionInfo) {
        if (USE_DRONE_CAMERA) {
            videoCapturer = DJIVideoCapturer()
            videoSource =
                connectionInfo.connectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        } else {
            val videoCapturer = createCameraCapturer(Camera2Enumerator(application))
            if (null == videoCapturer) {
                Log.e(TAG, "unable to create the video capturer!")
                return
            }
            this.videoCapturer = videoCapturer
            videoSource =
                connectionInfo.connectionFactory.createVideoSource(videoCapturer.isScreencast)

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
        }

        videoCapturer?.let {
            it.initialize(
                SurfaceTextureHelper.create("CaptureThread", connectionInfo.eglBase.eglBaseContext),
                application, videoSource!!.capturerObserver
            )
            it.startCapture(1280, 720, 30)

            val localVideoTrack =
                connectionInfo.connectionFactory.createVideoTrack("videoTrack", videoSource)
            videoTrackUpdate.postValue(
                VideoTrackAdded(connectionInfo.eglBase, localVideoTrack, it, USE_DRONE_CAMERA)
            )
            val sender = connectionInfo.connection.addTrack(localVideoTrack, listOf("streamId"))
            val parameters = sender.parameters
            for (parameter in parameters.encodings) {
                parameter.minBitrateBps = 4500000
                parameter.maxBitrateBps = 6000000
                parameter.maxFramerate = 60
            }
            sender.parameters = parameters
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

    private fun getRequiredPermissions(): List<String> {
        val reqPermissions = ArrayList<String>()
        for (permission in permissions) {
            if (PermissionChecker.checkSelfPermission(
                    application,
                    permission
                ) != PERMISSION_GRANTED
            ) {
                reqPermissions.add(permission)
            }
        }
        return reqPermissions
    }

    private fun showMessageOnLogAndScreen(level: Int, msg: String, exception: Exception? = null) {
        Log.println(level, TAG, "$msg${exception?.let { "\n" + Log.getStackTraceString(it) } ?: ""}")

        viewModelScope.launch {
            message.emit(level to msg)
        }
    }
}