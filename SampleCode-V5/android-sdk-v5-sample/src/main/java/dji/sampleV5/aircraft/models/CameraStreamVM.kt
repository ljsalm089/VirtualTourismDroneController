package dji.sampleV5.aircraft.models

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.core.content.PermissionChecker
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dji.sampleV5.aircraft.DJIVideoCapturer
import dji.sampleV5.aircraft.webrtc.ConnectionInfo
import dji.sampleV5.aircraft.webrtc.DataFromChannel
import dji.sampleV5.aircraft.webrtc.EVENT_CREATE_CONNECTION_ERROR
import dji.sampleV5.aircraft.webrtc.EVENT_CREATE_CONNECTION_SUCCESS
import dji.sampleV5.aircraft.webrtc.EVENT_EXCHANGE_OFFER_ERROR
import dji.sampleV5.aircraft.webrtc.EVENT_EXCHANGE_OFFER_SUCCESS
import dji.sampleV5.aircraft.webrtc.EVENT_RECEIVED_DATA
import dji.sampleV5.aircraft.webrtc.VIDEO_PUBLISHER
import dji.sampleV5.aircraft.webrtc.WebRtcEvent
import dji.sampleV5.aircraft.webrtc.WebRtcManager
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

private const val useDroneCamera = false

private const val PING_INTERVAL = 200L

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

class CameraStreamVM : ViewModel(), Consumer<WebRtcEvent> {

    private lateinit var webRtcManager: WebRtcManager
    private lateinit var eventDisposable: Disposable
    private lateinit var application: Application

    val requestPermissions = MutableLiveData<List<String>>()
    val message = MutableLiveData<String>()
    val videoTrackUpdate = MutableLiveData<VideoTrackAdded>()

    private var videoCapturer: VideoCapturer? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    private var tmpPermission = listOf<String>()

    fun initialize(application: Application) {
        this.application = application
        webRtcManager = WebRtcManager(scope = viewModelScope, application)
        eventDisposable = webRtcManager.webRtcEventObservable.subscribe(this)
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
            message.postValue("Have not enough permissions")
        }
    }

    fun startPublish() {
        webRtcManager.start()
    }

    fun stopPublish() {
        webRtcManager.stop()

        audioSource?.dispose()
        videoCapturer?.dispose()
        videoSource?.dispose()

        audioSource = null
        videoCapturer = null
        videoSource = null
    }

    fun sendData() {
        webRtcManager.sendData("Hello: ${System.currentTimeMillis()}", "Hello")
    }

    override fun accept(t: WebRtcEvent) {
        if (EVENT_CREATE_CONNECTION_SUCCESS == t.event) {
            val connectionInfo = t.data as? ConnectionInfo
            if (VIDEO_PUBLISHER == connectionInfo?.identity) {
                attachVideoAndAudioToConnection(connectionInfo)
            }
        } else if (EVENT_CREATE_CONNECTION_ERROR == t.event) {
            // create connection error, the data is null
            Log.e(TAG, "Failed to create a connection")
        } else if (EVENT_EXCHANGE_OFFER_ERROR == t.event) {
            if (t.data is Exception) {
                Log.e(
                    TAG,
                    "Got an error while exchanging the offer with server",
                    t.data as? Exception
                )
            } else {
                // string
                Log.e(TAG, "Got an error while exchanging the offer with server: ${t.data}")
            }
        } else if (EVENT_RECEIVED_DATA == t.event) {
            val data = t.data as? DataFromChannel
            data?.let {
                onReceivedData(data)
            }
        } else if (EVENT_EXCHANGE_OFFER_SUCCESS == t.event) {
            // exchange offer successfully, start periodic task to send ping to the headset
            if (t.data == VIDEO_PUBLISHER) {
                startPeriodicTask()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventDisposable.dispose()

        webRtcManager.stop()
    }

    private fun onReceivedData(data: DataFromChannel) {
        Log.d(TAG, "Got message from ${data.identity}.${data.channel}: ${data.data}")

    }

    private fun startPeriodicTask() {
        viewModelScope.launch {
            while (null != videoSource && isActive) {
                delay(PING_INTERVAL)

                webRtcManager.sendData("${System.currentTimeMillis()}", "Ping")
            }
        }
    }

    private fun attachVideoAndAudioToConnection(connectionInfo: ConnectionInfo) {
        if (useDroneCamera) {
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
                VideoTrackAdded(connectionInfo.eglBase, localVideoTrack, it, useDroneCamera)
            )
            connectionInfo.connection.addTrack(localVideoTrack, listOf("streamId"))
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
}