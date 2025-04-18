package dji.sampleV5.aircraft.models

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dji.sampleV5.aircraft.DJIVideoCapturer
import dji.sampleV5.aircraft.webrtc.ConnectionInfo
import dji.sampleV5.aircraft.webrtc.DataFromChannel
import dji.sampleV5.aircraft.webrtc.EVENT_CREATE_CONNECTION_ERROR
import dji.sampleV5.aircraft.webrtc.EVENT_CREATE_CONNECTION_SUCCESS
import dji.sampleV5.aircraft.webrtc.EVENT_EXCHANGE_OFFER_ERROR
import dji.sampleV5.aircraft.webrtc.EVENT_RECEIVED_DATA
import dji.sampleV5.aircraft.webrtc.VIDEO_PUBLISHER
import dji.sampleV5.aircraft.webrtc.WebRtcEvent
import dji.sampleV5.aircraft.webrtc.WebRtcManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.functions.Consumer
import org.webrtc.AudioSource
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource

private const val useDroneCamera = false

class CameraStreamVM(application: Application) : ViewModel(), Consumer<WebRtcEvent> {

    private var webRtcManager: WebRtcManager = WebRtcManager(scope = viewModelScope, application)
    private val eventDisposable: Disposable =
        webRtcManager.webRtcEventObservable.observeOn(AndroidSchedulers.mainThread())
            .subscribe(this)

    private var videoCapturer: VideoCapturer? = null

    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null

    init {

    }

    fun startPublish() {
        webRtcManager.start()
    }

    fun stopPublish() {
        webRtcManager.stop()
    }

    fun sendData() {

    }

    override fun accept(t: WebRtcEvent) {
        if (EVENT_CREATE_CONNECTION_SUCCESS == t.event) {
            val connectionInfo = t.data as? ConnectionInfo
            if (VIDEO_PUBLISHER == connectionInfo?.identity) {
                attachVideoAndAudioToConnection(connectionInfo)
            }
        } else if (EVENT_CREATE_CONNECTION_ERROR == t.event) {
            // create connection error, the data is null
        } else if (EVENT_EXCHANGE_OFFER_ERROR == t.event) {
            if (t.data is Exception) {

            } else {
                // string
            }
        } else if (EVENT_RECEIVED_DATA == t.event) {
            val data = t.data as? DataFromChannel
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventDisposable.dispose()

        webRtcManager.stop()
    }

    private fun attachVideoAndAudioToConnection(connectionInfo: ConnectionInfo) {
        if (useDroneCamera) {
            videoCapturer = DJIVideoCapturer()
            videoSource =
                connectionInfo.connectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        }
    }
}