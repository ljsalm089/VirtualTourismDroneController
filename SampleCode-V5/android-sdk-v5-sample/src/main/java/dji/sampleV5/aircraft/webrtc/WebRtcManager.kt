package dji.sampleV5.aircraft.webrtc

import android.app.Application
import android.util.Log
import com.google.gson.Gson
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


const val TYPE_DATA = "data"

const val TYPE_AUDIO = "audio"

const val TYPE_VIDEO = "video"

const val VIDEO_PUBLISHER = "videoPublisher"

const val DATA_RECEIVER = "dataReceiver"

const val STUN_SERVER = "stun:stun.l.google.com:19302"

private const val TAG = "WebRtcManager"

const val EVENT_CREATE_CONNECTION_ERROR_FOR_PUBLICATION = "create_connection_error"
const val EVENT_CREATE_CONNECTION_SUCCESS_FOR_PUBLICATION = "create_connection_success"
const val EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION = "exchange_offer_error"
const val EVENT_EXCHANGE_OFFER_SUCCESS_FOR_PUBLICATION = "exchange_offer_success"

const val EVENT_CREATE_CONNECTION_ERROR_FOR_SUBSCRIPTION = "create_connection_error_for_subscription"
const val EVENT_CREATE_CONNECTION_SUCCESS_FOR_SUBSCRIPTION = "create_connection_success_for_subscription"
const val EVENT_EXCHANGE_OFFER_ERROR_FOR_SUBSCRIPTION = "exchange_offer_error_for_subscription"
const val EVENT_EXCHANGE_OFFER_SUCCESS_FOR_SUBSCRIPTION = "exchange_offer_success_for_subscription"

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

    private val connections = HashMap<String, BaseWebRtcConnection>()

    private val eglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory

    private var mExChange: WebSocketOfferExchange? = null

    private val headsetStatusCallBack: (String, Any?) -> Unit = { it, data ->
        if (EVENT_HEADSET_ONLINE == it && !connections.contains(DATA_RECEIVER)) {
            // create a connection for receiving data
            val supportTypes = hashSetOf(
                TYPE_DATA
            )
            val subscribeOfferExchange = object : IOfferExchange {
                override suspend fun exchangeOffer(localOffer: String): String {
                    TODO("Shouldn't be called in this situation")
                }

                override suspend fun fetchRemoteOffer(supportTypes: Set<String>): String {
                    return mExChange!!.startSubscribe(supportTypes, data)
                }

                override suspend fun uploadLocalAnswer(offer: String) {
                    mExChange!!.uploadLocalAnswer(offer)
                }

                override suspend fun destroy() {
                }

            }
            val conn = SubscriptionConnection(
                DATA_RECEIVER, supportTypes, subscribeOfferExchange,
                this, this, eglBase, scope
            )
            connections[DATA_RECEIVER] = conn
            conn.connect()
        } else if (EVENT_HEADSET_OFFLINE == it && connections.contains(DATA_RECEIVER)) {
            // destroy the connection for data receiving
            connections[DATA_RECEIVER]?.disconnect()
            connections.remove(DATA_RECEIVER)
            mExChange?.stopSubscribe()
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

            override suspend fun fetchRemoteOffer(supportTypes: Set<String>): String {
                TODO("Shouldn't be called in this situation")
            }

            override suspend fun uploadLocalAnswer(offer: String) {
                TODO("Shouldn't be called in this situation")
            }

            override suspend fun destroy() {
            }

        }

        val conn = PublicationConnection(
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
        for (keyAndValue in connections.entries) {
            keyAndValue.value.disconnect()
        }
        connections.clear()

        mExChange?.destroy()
    }

    override fun emit(event: WebRtcEvent) {
        // filter out all message from this end
        webRtcEventObservable.onNext(event)
    }

    override fun get(): PeerConnectionFactory {
        return peerConnectionFactory
    }

}

abstract class BaseWebRtcConnection (
    protected val identity: String,
    protected val supportTypes: Set<String>,
    protected val offerExchange: IOfferExchange,
    protected val eventEmitter: IWebRtcEventEmitter,
    protected val connectionFactoryProvider: IConnectionFactoryProvider,
    protected val eglBase: EglBase,
    protected val scope: CoroutineScope,
) {
    protected lateinit var connection: PeerConnection
    protected val receivedChannels: HashMap<String, DataChannel> = HashMap()
    protected val dataObservers: HashMap<String, DataChannel.Observer> = HashMap()
    protected var transmitDataChannel: DataChannel? = null


    open fun sendData(data: MutableMap<String, Any?>) {
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

    open fun connect() {
        val iceServers = listOf(
            IceServer(STUN_SERVER)
        )
        val rtcConfig = RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val conn =
            connectionFactoryProvider.get().createPeerConnection(rtcConfig, object : Observer {
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
            eventEmitter.emit(WebRtcEvent(getConnectionEvent(false), null))
            return
        }
        connection = conn
        eventEmitter.emit(
            WebRtcEvent(
                getConnectionEvent(true),
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
    }

    open fun disconnect() {
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

    abstract fun getConnectionEvent(success: Boolean) : String

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
}

class SubscriptionConnection(
    identity: String, supportTypes: Set<String>, offerExchange: IOfferExchange,
    eventEmitter: IWebRtcEventEmitter, connectionFactoryProvider: IConnectionFactoryProvider,
    eglBase: EglBase, scope: CoroutineScope,
) : BaseWebRtcConnection(
    identity,
    supportTypes,
    offerExchange,
    eventEmitter,
    connectionFactoryProvider,
    eglBase,
    scope
) {

    override fun connect() {
        super.connect()

        // for subscriber, setup the remote offer first
        scope.launch (Dispatchers.IO) {
            val remoteOffer = offerExchange.fetchRemoteOffer(supportTypes)
            val sdp = SessionDescription(SessionDescription.Type.OFFER, remoteOffer)

            try {
                setupRemoteOffer(sdp)
            } catch (e: Exception) {
                eventEmitter.emit(WebRtcEvent(EVENT_EXCHANGE_OFFER_ERROR_FOR_SUBSCRIPTION, e))
                return@launch
            }

            // generate answer and return to remote server
            try {
                val localAnswer = createLocalAnswer()
                setupLocalAnswer(localAnswer)
                offerExchange.uploadLocalAnswer(localAnswer.description)

            } catch (e: Exception) {
                eventEmitter.emit(WebRtcEvent(EVENT_EXCHANGE_OFFER_ERROR_FOR_SUBSCRIPTION, e))
                return@launch
            }

           eventEmitter.emit(WebRtcEvent(EVENT_EXCHANGE_OFFER_SUCCESS_FOR_SUBSCRIPTION, ""))
        }
    }

    override fun getConnectionEvent(success: Boolean): String {
        return if (success) EVENT_CREATE_CONNECTION_SUCCESS_FOR_SUBSCRIPTION else EVENT_CREATE_CONNECTION_ERROR_FOR_SUBSCRIPTION
    }

    private suspend fun createLocalAnswer(): SessionDescription = suspendCancellableCoroutine {
        connection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                it.resume(p0!!)
            }

            override fun onSetSuccess() {
                TODO("Shouldn't be called in this situation")
            }

            override fun onCreateFailure(p0: String?) {
                it.resumeWithException(Exception("Failed to create local answer for subscription"))
            }

            override fun onSetFailure(p0: String?) {
                TODO("Shouldn't be called in this situation")
            }

        }, MediaConstraints())
    }

    private suspend fun setupRemoteOffer(sdp: SessionDescription) = suspendCancellableCoroutine<Any?> {
        connection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                TODO("Shouldn't be called in this situation")
            }

            override fun onSetSuccess() {
                // continue
                it.resume(null)
            }

            override fun onCreateFailure(p0: String?) {
                TODO("Shouldn't be called in this situation")
            }

            override fun onSetFailure(p0: String?) {
                it.resumeWithException(Exception("Failed to setup remote offer for subscription"))
            }

        }, sdp)
    }

    private suspend fun setupLocalAnswer(sdp: SessionDescription) = suspendCancellableCoroutine<Any?> {
        connection.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                TODO("Shouldn't be called in this situation")
            }

            override fun onSetSuccess() {
                it.resume(null)
            }

            override fun onCreateFailure(p0: String?) {
                TODO("Shouldn't be called in this situation")
            }

            override fun onSetFailure(p0: String?) {
                it.resumeWithException(Exception("Failed to setup local answer for subscription"))
            }

        }, sdp)
    }
}

class PublicationConnection(
    identity: String, supportTypes: Set<String>, offerExchange: IOfferExchange,
    eventEmitter: IWebRtcEventEmitter, connectionFactoryProvider: IConnectionFactoryProvider,
    eglBase: EglBase, scope: CoroutineScope,
) : BaseWebRtcConnection(
    identity,
    supportTypes,
    offerExchange,
    eventEmitter,
    connectionFactoryProvider,
    eglBase,
    scope
) {

    override fun connect() {
        super.connect()

        // for publisher, setup the local offer first
        connection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                p0?.let {
                    connection.setLocalDescription(this, p0)
                    return
                }

                eventEmitter.emit(
                    WebRtcEvent(
                        EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION,
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
                        EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION,
                        "Unable to create local offer!"
                    )
                )
            }

            override fun onSetFailure(p0: String?) {
                eventEmitter.emit(
                    WebRtcEvent(
                        EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION,
                        "Unable to set local offer!"
                    )
                )
            }
        }, MediaConstraints())
    }

    override fun getConnectionEvent(success: Boolean): String {
        return if(success) EVENT_CREATE_CONNECTION_SUCCESS_FOR_PUBLICATION else EVENT_CREATE_CONNECTION_ERROR_FOR_PUBLICATION
    }

    private fun exchangeOffer(offer: SessionDescription) {
        scope.launch(Dispatchers.IO) {
            try {
                val remoteOffer = offerExchange.exchangeOffer(offer.description)
                scope.launch(Dispatchers.Main) { setRemoteOffer(remoteOffer) }
            } catch (e: Exception) {
                eventEmitter.emit(WebRtcEvent(EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION, "Network error: $e"))
            }
        }
    }

    private fun setRemoteOffer(offer: String) {
        connection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
            }

            override fun onSetSuccess() {
                eventEmitter.emit(WebRtcEvent(EVENT_EXCHANGE_OFFER_SUCCESS_FOR_PUBLICATION, identity))
            }

            override fun onCreateFailure(p0: String?) {
            }

            override fun onSetFailure(p0: String?) {
                eventEmitter.emit(
                    WebRtcEvent(
                        EVENT_EXCHANGE_OFFER_ERROR_FOR_PUBLICATION,
                        "Unable to set remote offer"
                    )
                )
            }

        }, SessionDescription(SessionDescription.Type.ANSWER, offer))
    }

}