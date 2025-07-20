package dji.sampleV5.aircraft.webrtc

import android.app.Application
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.Observer
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer


const val TYPE_DATA = "data"

const val TYPE_AUDIO = "audio"

const val TYPE_VIDEO = "video"

const val VIDEO_PUBLISHER = "videoPublisher"

const val DATA_RECEIVER = "dataReceiver"

const val STUN_SERVER = "stun:stun.l.google.com:19302"

private const val TAG = "WebRtcManager"

const val EVENT_CREATE_CONNECTION_ERROR = "create_connection_error"
const val EVENT_CREATE_CONNECTION_SUCCESS = "create_connection_success"
const val EVENT_EXCHANGE_OFFER_ERROR = "exchange_offer_error"
const val EVENT_EXCHANGE_OFFER_SUCCESS = "exchange_offer_success"

const val EVENT_HEADSET_ONLINE = "headset_online"

const val EVENT_HEADSET_OFFLINE = "headset_offline"

const val EVENT_RECEIVED_DATA = "received_data"

const val KEEP_ALIVE_INTERVAL = 1000L

const val FROM_DRONE = "Drone"


data class WebRtcEvent(val event: String, val data: Any?)

data class ConnectionInfo(
    val identity: String,
    val connection: PeerConnection,
    val eglBase: EglBase,
    val connectionFactory: PeerConnectionFactory
)

data class DataFromChannel(val identity: String, val data: String, val channel: String)

interface IWebRtcEventEmitter {

    fun emit(event: WebRtcEvent)

}

interface IConnectionFactoryProvider {

    fun get(): PeerConnectionFactory

}

class WebRtcManager(private val scope: CoroutineScope, private val application: Application) :
    IWebRtcEventEmitter, IConnectionFactoryProvider {

    val webRtcEventObservable = PublishSubject.create<WebRtcEvent>()

    private lateinit var periodicJob: Job

    private val connections = HashMap<String, WebRtcConnection>()

    private val eglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory

    private var mExChange: WebSocketOfferExchange? = null

    private val headsetStatusCallBack: (String) -> Unit = {
        if (EVENT_HEADSET_ONLINE == it) {

            // create a connection for receiving data
            val supportTypes = hashSetOf(
                TYPE_DATA
            )
            val subscribeOfferExchange = object : IOfferExchange {
                override suspend fun exchangeOffer(localOffer: String): String {
                    return mExChange!!.startSubscribe(localOffer)
                }

                override suspend fun destroy() {
                }

            }
            val conn = WebRtcConnection(
                DATA_RECEIVER, supportTypes, subscribeOfferExchange,
                this, this, eglBase, scope
            )
            connections[DATA_RECEIVER] = conn
            conn.connection
        } else if (EVENT_HEADSET_OFFLINE == it) {

        }
    }

    init {
        val option = PeerConnectionFactory.InitializationOptions.builder(application)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(option)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    fun start() {
        mExChange = WebSocketOfferExchange(scope, 5000L, headsetStatusCallBack)

        // create a connection for publishing video
        val supportTypes = hashSetOf(
            TYPE_VIDEO,
            TYPE_AUDIO,
            TYPE_DATA
        )
        val publisherOfferExchange = object : IOfferExchange {
            override suspend fun exchangeOffer(localOffer: String): String {
                return mExChange!!.startPublish(localOffer)
            }

            override suspend fun destroy() {
            }

        }
        var conn = WebRtcConnection(
            VIDEO_PUBLISHER, supportTypes, publisherOfferExchange,
            this, this, eglBase, scope
        )
        connections[VIDEO_PUBLISHER] = conn

        for (keyAndValue in connections.entries) {
            keyAndValue.value.connect()
        }

    }

    fun sendData(dataString: String, type: String) {
        val data = HashMap<String, Any?>()
        data["data"] = dataString
        data["type"] = type
        data["from"] = FROM_DRONE
        connections[VIDEO_PUBLISHER]?.sendData(data)
    }

    fun stop() {
        if (this::periodicJob.isInitialized) {
            this.periodicJob.cancel()
        }

        for (keyAndValue in connections.entries) {
            keyAndValue.value.disconnect()
        }
        connections.clear()
    }

    override fun emit(event: WebRtcEvent) {
        // filter out all message from this end
        if (EVENT_RECEIVED_DATA == event.event && event.data is DataFromChannel) {
            val data = Gson().fromJson(event.data.data, object: TypeToken<Map<String, Any?>>() {})

            if (FROM_DRONE == data["from"]) {
                return
            }
        }
        webRtcEventObservable.onNext(event)
    }

    override fun get(): PeerConnectionFactory {
        return peerConnectionFactory
    }

    private fun executePeriodicTask() {
        periodicJob = scope.launch {
            while (isActive) {
                // keep alive
                for (keyAndValue in connections) {
                    keyAndValue.value.keepAlive()
                }

                delay(KEEP_ALIVE_INTERVAL)
            }
        }
    }
}

class WebRtcConnection(
    private val identity: String,
    private val supportTypes: Set<String>,
    private val offerExchange: IOfferExchange,
    private val eventEmitter: IWebRtcEventEmitter,
    private val connectionFactoryProvider: IConnectionFactoryProvider,
    private val eglBase: EglBase,
    private val scope: CoroutineScope,
) {

    lateinit var connection: PeerConnection
    private val receivedChannels: HashMap<String, DataChannel> = HashMap()
    private val dataObservers: HashMap<String, DataChannel.Observer> = HashMap()
    private var transmitDataChannel: DataChannel? = null

    fun connect() {
        val iceServers = listOf(
            IceServer(STUN_SERVER)
        )
        val rtcConfig = RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val conn = connectionFactoryProvider.get().createPeerConnection(rtcConfig, object : Observer {
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                Log.d(TAG, "onSignalingChange: $p0")
            }

            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection State: $p0")
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: $p0")
            }

            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "onIceGatheringChange: $p0")
            }

            override fun onIceCandidate(p0: IceCandidate?) {
                Log.d(TAG, "ICE Candidate: ${p0?.sdp}")
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
                Log.d(TAG, "onIceCandidatesRemoved")
            }

            override fun onAddStream(p0: MediaStream?) {
            }

            override fun onRemoveStream(p0: MediaStream?) {
            }

            override fun onDataChannel(p0: DataChannel?) {
                p0?.let {
                    onReceivedDataChannel(it)
                }
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenogotiationNeeded")
            }
        })
        if (null == conn) {
            eventEmitter.emit(WebRtcEvent(EVENT_CREATE_CONNECTION_ERROR, null))
            return
        }
        connection = conn
        eventEmitter.emit(
            WebRtcEvent(
                EVENT_CREATE_CONNECTION_SUCCESS,
                ConnectionInfo(identity, connection, eglBase, connectionFactoryProvider.get())
            )
        )
        if (supportTypes.contains(TYPE_DATA)) {
            // create the test channel to make sure the offer contains the information to support data channels
            val init = DataChannel.Init()
            init.ordered = true
            init.protocol = "json"
            init.id = 10000
            connection.createDataChannel("test", init)
        }

        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                p0?.let {
                    connection.setLocalDescription(this, p0)
                    return
                }

                eventEmitter.emit(
                    WebRtcEvent(
                        EVENT_EXCHANGE_OFFER_ERROR,
                        "Unable to create valid local offer!"
                    )
                )
            }

            override fun onSetSuccess() {
                exchangeOffer(connection.localDescription)
            }

            override fun onCreateFailure(p0: String?) {
                eventEmitter.emit(
                    WebRtcEvent(
                        EVENT_EXCHANGE_OFFER_ERROR,
                        "Unable to create local offer!"
                    )
                )
            }

            override fun onSetFailure(p0: String?) {
                eventEmitter.emit(
                    WebRtcEvent(
                        EVENT_EXCHANGE_OFFER_ERROR,
                        "Unable to set local offer!"
                    )
                )
            }
        }, MediaConstraints())
    }

    fun keepAlive() {
        scope.launch(Dispatchers.IO) {
            for (kvp in receivedChannels.entries) {
                kvp.value.send(DataChannel.Buffer(ByteBuffer.wrap("Ping".toByteArray()), false))
            }
        }
    }

    fun sendData(data: MutableMap<String, Any?>) {
        if (!this::connection.isInitialized) return

        if (null == transmitDataChannel) {
            val init = DataChannel.Init()
            init.ordered = true
            init.protocol = "json"
            transmitDataChannel = connection.createDataChannel("DronePosFeedBack", init)
            transmitDataChannel?.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(p0: Long) {
                }

                override fun onStateChange() {
                    if (transmitDataChannel?.state() == DataChannel.State.CLOSED) {
                        transmitDataChannel = null
                    }
                }

                override fun onMessage(p0: DataChannel.Buffer?) {
                    // in theory, won't receive any data from this channel, just ignore it
                }
            })
        }
        data["channel"] = transmitDataChannel?.label()
        val sendData = Gson().toJson(data)
        // In theory, all the data sent from the transmit data channel will be received on the received channels
        transmitDataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(sendData.toByteArray()), false))
    }

    fun disconnect() {
        if (!this::connection.isInitialized) return

        for (kav in receivedChannels.entries) {
            kav.value.dispose()
        }
        this.transmitDataChannel?.let {
            it.dispose()
            this.transmitDataChannel = null
        }
        this.connection.dispose()
    }

    private fun onReceivedDataChannel(channel: DataChannel) {
        if (receivedChannels.contains(channel.label())) return

        receivedChannels[channel.label()] = channel

        val dataObserver = object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {
                Log.d(TAG, "onBufferedAmountChange: $p0")
            }

            override fun onStateChange() {
                Log.d(TAG, "onStateChange: ${channel.state()}")
            }

            override fun onMessage(p0: DataChannel.Buffer?) {
                p0?.let {
                    val byteArray = ByteArray(it.data.remaining())
                    it.data.get(byteArray)
                    val message = byteArray.decodeToString()
                    Log.d(TAG, "Got message from channel: ${identity}.${channel.label()}: $message")

                    eventEmitter.emit(
                        WebRtcEvent(
                            EVENT_RECEIVED_DATA, DataFromChannel(identity, message, channel.label())
                        )
                    )
                }
            }

        }
        channel.registerObserver(dataObserver)
        dataObservers[channel.label()] = dataObserver
    }

    private fun exchangeOffer(offer: SessionDescription) {
        scope.launch(Dispatchers.IO) {
            try {
                val remoteOffer = offerExchange.exchangeOffer(offer.description)
                scope.launch(Dispatchers.Main) { setRemoteOffer(remoteOffer) }
            } catch (e: Exception) {
                eventEmitter.emit(WebRtcEvent(EVENT_EXCHANGE_OFFER_ERROR, "Network error: $e"))
            }
        }
    }

    private fun setRemoteOffer(offer: String) {
        connection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
            }

            override fun onSetSuccess() {
                eventEmitter.emit(WebRtcEvent(EVENT_EXCHANGE_OFFER_SUCCESS, identity))
            }

            override fun onCreateFailure(p0: String?) {
            }

            override fun onSetFailure(p0: String?) {
                eventEmitter.emit(
                    WebRtcEvent(
                        EVENT_EXCHANGE_OFFER_ERROR,
                        "Unable to set remote offer"
                    )
                )
            }

        }, SessionDescription(SessionDescription.Type.ANSWER, offer))
    }

}