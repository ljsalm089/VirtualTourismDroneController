package dji.sampleV5.aircraft.webrtc

import android.text.TextUtils
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.AbstractMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.Pair
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OfferExchanger"

private const val SUCCESS = "success"

private const val VIDEO_ROOM_PLUGIN = "janus.plugin.videoroom"

private const val iceServerUrl = "stun:stun.l.google.com:1930";

private const val wsServerProtocolAndHostPort = "ws://10.112.53.217:8188"

private const val needProxy = false

private const val proxyHost = "10.112.53.217"

private const val proxyPort = 8888

private const val TIMEOUT_INTERVAL = 5000L

interface IOfferExchange {

    suspend fun exchangeOffer(localOffer: String): String

    suspend fun fetchRemoteOffer(supportTypes: Set<String>): String

    suspend fun updateLocalOffer(offer: String)

    suspend fun destroy()
}

data class EndPoint(
    var sessionId: String? = null,
    var handleId: String? = null,
)

internal fun EndPoint.isValid(): Boolean {
    return !TextUtils.isEmpty(sessionId) && !TextUtils.isEmpty(handleId)
}

internal fun randomUUID(): String {
    return UUID.randomUUID().toString()
}

open class BaseJanusBean(var janus: String? = null) {
    var transaction: String? = randomUUID()
}

data class SimpleJanusResponse<T>(var data: Map<String, T?>?) : BaseJanusBean()

data class PluginData(
    var plugin: String? = null,
    var data: Map<String, Any?>? = null,
)

data class Error(
    var code: Int = 0,
    var reason: String? = null,
)

data class JSEPData(var type: String? = null, var sdp: String? = null)

data class JanusPluginResponse(
    var plugindata: PluginData? = null,
    var error: Error? = null,
    var jsep: JSEPData? = null,
) : BaseJanusBean()

data class JanusRequest(
    @SerializedName("session_id") var sessionId: Long? = null,
    @SerializedName("plugin") var plugin: String? = null,
    @SerializedName("handle_id") var handleId: Long? = null,
) : BaseJanusBean()

interface TransactionFilter {

    fun isInvalidMsgForTransaction(result: Map<String, Any?>): Boolean

}

fun <T> CancellableContinuation<T>.safeResume(value: T) {
    if (!this.isCompleted && !this.isCancelled) {
        this.resume(value)
    }
}

fun <T> CancellableContinuation<T>.safeResumeWithException(e: Exception) {
    if (!this.isCompleted && !this.isCancelled) {
        this.resumeWithException(e)
    }
}

class WebSocketOfferExchange(
    val scope: CoroutineScope,
    val intervalToKeepAlive: Long,
    var headsetStatusCallBack: ((String) -> Unit)?,
) : WebSocketListener() {

    val TAG = "WebSocketOfferExchange"

    val FIELD_ID = "id"

    val ROOM_ID = 1234

    val ROOM_PIN = "adminpwd"

    val VIDEO_PUBLISHER_ID = 123456789

    val VIDEO_PUBLISHER_DISPLAY = "DroneController"

    val DATA_PUBLISHER_ID = 987654321

    val DATA_PUBLISHER_DISPLAY = "Headset"

    private val waitingTransactions: AbstractMap<String, CancellableContinuation<String>?> =
        ConcurrentHashMap<String, CancellableContinuation<String>?>()

    private val waitingTransactionsFilters: AbstractMap<String, TransactionFilter> =
        ConcurrentHashMap()

    private var webSocket: WebSocket? = null

    private var continuation: CancellableContinuation<WebSocket>? = null

    private val mutex = Mutex()

    private lateinit var gson: Gson

    private var job: Job? = null

    private var publishEndPoint: EndPoint = EndPoint()

    private var subscribeEndPoint: EndPoint = EndPoint()

    private suspend fun initWebSocket(): WebSocket = suspendCancellableCoroutine {
        val okHttpClient = OkHttpClient.Builder().apply {
            if (needProxy) {
                this.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
            }
        }.build()

        val request = Request.Builder().url(wsServerProtocolAndHostPort)
            .header("Sec-WebSocket-Protocol", "janus-protocol").build()
        continuation = it
        okHttpClient.newWebSocket(request, this)
        okHttpClient.dispatcher.executorService.shutdown()
        scope.launch(Dispatchers.IO) {
            delay(TIMEOUT_INTERVAL)

            continuation?.safeResumeWithException(TimeoutException("Building websocket connection timeout"))
            continuation = null
        }
    }

    private fun handleMessage(msg: String) {
        val result = gson.fromJson(msg, object : TypeToken<Map<String, Any?>>() {})
        val fieldJanus = "janus"
        if ("ack" == result[fieldJanus]) {
            // ignore this kind of message
            return
        }

        result["transaction"]?.toString()?.apply {
            if (waitingTransactionsFilters.get(this)?.isInvalidMsgForTransaction(result) == true) {
                return@apply
            }
            handleTransaction(this, msg, Exception())
        }

        val event = "event"

        if (event == result[fieldJanus]
            && VIDEO_ROOM_PLUGIN == (result.get("plugindata") as? Map<String, Any?>?)?.get("plugin")
        ) {
            val data: Map<String, Any?>? =
                (result["plugindata"] as? Map<String, Any?>)?.get("data") as? Map<String, Any?>?
            if (event == data?.get("videoroom") && ROOM_ID == data["room"]) {
                // right here, using Any to deserialize the json, 'unpublished' should be Double
                // TODO need to be confirmed
                if (DATA_PUBLISHER_ID == data["unpublished"]) {
                    scope.launch(Dispatchers.Main) {
                        // the headset went offline
                        headsetStatusCallBack?.invoke(EVENT_HEADSET_OFFLINE)
                    }
                } else {
                    handleIfDataPublisherIsOnline(data)
                }
            }
        }
    }

    private fun handleIfDataPublisherIsOnline(data: Map<String, Any?>?) {
        if (data?.get("publishers") is Collection<*>) {
            val publishers: Collection<*> =
                data["publishers"] as Collection<*>

            for (tmp in publishers) {
                val publisher: Map<String, *>? = tmp as? Map<String, *>
                // TODO the type of id should be confirmed first, because of the way of deserialize json
                val id: Long = (publisher?.get(FIELD_ID) as? Double?)?.toLong() ?: 0
                if (DATA_PUBLISHER_ID.toLong() == id) {
                    scope.launch(Dispatchers.Main) {
                        // the headset is publishing data, make sure the invocation is on UI thread
                        headsetStatusCallBack?.invoke(EVENT_HEADSET_ONLINE)
                    }
                    break
                }
            }
        }
    }

    private fun handleTransaction(transaction: String, result: String?, exception: Exception) {
        val continuation: CancellableContinuation<String>? = waitingTransactions.remove(transaction)
        waitingTransactionsFilters.remove(transaction)

        continuation?.let {
            if (null != result) {
                it.safeResume(result)
            } else {
                it.safeResumeWithException(exception)
            }
        } ?: {
            // TODO handle the message without transaction id
        }
    }

    private suspend fun sendMessageToServer(transaction: String, req: Any): String =
        suspendCancellableCoroutine {
            scope.launch(Dispatchers.IO) {
                val text: String = gson.toJson(req)
                waitingTransactions[transaction] = it
                Log.d(TAG, "Add message to queue: $text")
                if (webSocket!!.send(text)) {
                    delay(TIMEOUT_INTERVAL)

                    handleTransaction(
                        transaction, null,
                        TimeoutException("Hasn't received result in $TIMEOUT_INTERVAL milliseconds")
                    )
                } else {
                    it.safeResumeWithException(IOException("Failed to add data into queue in WebSocket"))
                    waitingTransactions.remove(transaction)
                    waitingTransactionsFilters.remove(transaction)
                }
            }
        }

    private suspend fun makeSureWebSocket(continuation: CancellableContinuation<String>) {
        if (null == webSocket) {
            mutex.withLock {
                if (null == webSocket) {
                    try {
                        webSocket = initWebSocket()

                        gson = GsonBuilder().create()
                        job = scope.launch(Dispatchers.IO) {
                            while (isActive) {
                                delay(intervalToKeepAlive)

                                val keepAliveReq = JanusRequest()
                                keepAliveReq.janus = "keepalive"

                                publishEndPoint.sessionId?.apply {
                                    keepAliveReq.sessionId = this.toLong()
                                    Log.d(TAG, "sending keep alive package to janus: $this")
                                    this@WebSocketOfferExchange.webSocket?.send(
                                        gson.toJson(
                                            keepAliveReq
                                        )
                                    )
                                }

                                subscribeEndPoint.sessionId?.apply {
                                    keepAliveReq.sessionId = this.toLong()
                                    Log.d(TAG, "sending keep alive package to janus: $this")
                                    this@WebSocketOfferExchange.webSocket?.send(
                                        gson.toJson(
                                            keepAliveReq
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        continuation.safeResumeWithException(e)
                    }
                    // wait until the connection is built successfully
                }
            }
        }
    }

    private fun createVideoRoomMessage(
        sessionId: Long,
        handleId: Long,
        transaction: String,
    ): HashMap<String, Any> {
        return hashMapOf(
            Pair("janus", "message"),
            Pair("session_id", sessionId),
            Pair("handle_id", handleId),
            Pair("transaction", transaction)
        )
    }

    private suspend fun makeSureSessionAndHandleId(
        publish: Boolean,
        continuation: CancellableContinuation<String>,
    ) {
        if ((publish && !publishEndPoint.isValid()) || (!publish && !subscribeEndPoint.isValid())) {
            mutex.withLock {
                var endPoint: EndPoint = if (publish) publishEndPoint else subscribeEndPoint
                if (TextUtils.isEmpty(endPoint.sessionId)) {
                    // make sure session is valid
                    val sessionResp = withContext(Dispatchers.IO) {
                        val req = BaseJanusBean("create")
                        sendMessageToServer(req.transaction!!, req)
                    }
                    // parse the result
                    val resp = gson.fromJson(
                        sessionResp,
                        object : TypeToken<SimpleJanusResponse<String>>() {})

                    if (SUCCESS != resp.janus || TextUtils.isEmpty(
                            resp.data?.get(FIELD_ID)
                        )
                    ) {
                        // return error
                        continuation.safeResumeWithException(Exception("Unable to create a session"))
                        return
                    }

                    endPoint.sessionId = resp.data?.get(FIELD_ID);
                }

                // attach session to the video room
                val janusReq = JanusRequest(endPoint.sessionId!!.toLong(), plugin = VIDEO_ROOM_PLUGIN)
                janusReq.janus = "attach"

                val handleIdResp = withContext(Dispatchers.IO) {
                    sendMessageToServer(janusReq.transaction!!, janusReq)
                }
                val resp = gson.fromJson(
                    handleIdResp,
                    object : TypeToken<SimpleJanusResponse<String>>() {})

                if (SUCCESS != resp.janus || TextUtils.isEmpty(
                        resp.data?.get(FIELD_ID)
                    )
                ) {
                    // return error
                    continuation.safeResumeWithException(Exception("Unable to attach a session to video room"))
                    return
                }

                endPoint.handleId = resp.data?.get(FIELD_ID)
            }
        }
    }

    private fun isPluginRespError(
        resp: JanusPluginResponse,
        continuation: CancellableContinuation<*>?,
    ): Boolean {
        if (!TextUtils.isEmpty(resp.plugindata?.data?.get("error")?.toString())) {
            continuation?.safeResumeWithException(
                Exception(
                    resp.plugindata?.data?.get("error")?.toString()!!
                )
            )
            return true
        } else if (!TextUtils.isEmpty(
                resp.plugindata?.data?.get("reason")?.toString()
            )
        ) {
            continuation?.safeResumeWithException(
                Exception(
                    resp.plugindata?.data?.get("reason")?.toString()!!
                )
            )
            return true
        }
        return false
    }

    suspend fun startPublish(localOffer: String): String = suspendCancellableCoroutine {
        var isCancel = false
        scope.launch(Dispatchers.IO) {
            makeSureWebSocket(it)

            if (null == webSocket) {
                it.safeResumeWithException(Exception("Unable to build the websocket connection"))
                return@launch
            }

            makeSureSessionAndHandleId(true, it)

            if (!publishEndPoint.isValid()) {
                it.safeResumeWithException(Exception("Unable to create session and handle id"))
                return@launch
            }

            if (isCancel) {
                return@launch
            }
            // INFO is handling the candidates mandatory? The answer is no, so just ignore this step

            // start publish video, audio and data, using the legacy way
            val transactionId = randomUUID()
            val publishReq = createVideoRoomMessage(
                publishEndPoint.sessionId!!.toLong(),
                publishEndPoint.handleId!!.toLong(),
                transactionId
            )
            publishReq["jsep"] = mapOf(
                Pair("type", "offer"),
                Pair("sdp", localOffer)
            )
            publishReq["body"] = mapOf<String, Any>(
                Pair("request", "joinandconfigure"),
                Pair("room", ROOM_ID),
                Pair("pin", ROOM_PIN),
                Pair("ptype", "publisher"),
                Pair("id", VIDEO_PUBLISHER_ID),
                Pair("display", VIDEO_PUBLISHER_DISPLAY),
                Pair("audio", true),
                Pair("video", true),
                Pair("data", true)
            )

            val resp = sendMessageToServer(transactionId, publishReq)

            val pluginResp = gson.fromJson(resp, JanusPluginResponse::class.java)

            if (isPluginRespError(pluginResp, it)) {
                return@launch
            } else { // success
                // optional, setting up forwarding, just for test if the video is published correctly
                val forwardingTransactionId = randomUUID()
                val forwardingReq = createVideoRoomMessage(
                    publishEndPoint.sessionId!!.toLong(), publishEndPoint.handleId!!.toLong(),
                    forwardingTransactionId
                )
                forwardingReq["body"] = mapOf(
                    Pair("request", "rtp_forward"),
                    Pair("room", ROOM_ID),
                    Pair("transaction", forwardingTransactionId),
                    Pair("publisher_id", VIDEO_PUBLISHER_ID),
                    Pair("secret", ROOM_PIN),
                    Pair("host", "127.0.0.1"),
                    Pair("host_family", "ipv4"),
                    Pair("audio_port", 5002),
                    Pair("video_port", 5004),
                    Pair("data_port", 5006)
                )
                // ask the video room plugin to forward data to a specific streaming room
                sendMessageToServer(forwardingTransactionId, forwardingReq)

                // check if the headset is publishing data. If so, notify others to start the subscription
                handleIfDataPublisherIsOnline(pluginResp.plugindata?.data)

                // return the remote offer
                it.safeResume(pluginResp.jsep?.sdp!!)
            }
        }
        it.invokeOnCancellation {
            isCancel = true
        }
    }

    suspend fun startSubscribe(subscribeTypes: Set<String>): String = suspendCancellableCoroutine {
        var isCancel = false

        scope.launch(Dispatchers.IO) {
            makeSureWebSocket(it)

            if (null == webSocket) {
                it.safeResumeWithException(Exception("Unable to build the websocket connection"))
                return@launch
            }

            makeSureSessionAndHandleId(false, it)

            if (!subscribeEndPoint.isValid()) {
                it.safeResumeWithException(Exception("Unable to create session and handle id"))
                return@launch
            }

            if (isCancel) return@launch

            val joinTransactionId = randomUUID()
            val joinReq = createVideoRoomMessage(
                subscribeEndPoint.sessionId!!.toLong(),
                subscribeEndPoint.handleId!!.toLong(),
                joinTransactionId
            )
            joinReq["body"] = mapOf(
                Pair("request", "join"),
                Pair("ptype", "subscriber"),
                Pair("room", ROOM_ID),
                Pair("pin", ROOM_PIN),
                Pair("feed", DATA_PUBLISHER_ID),
                Pair("audoupdate", false),
            )

            val resp = sendMessageToServer(joinTransactionId, joinReq)
            val pluginResp = gson.fromJson(resp, JanusPluginResponse::class.java)

            if (isPluginRespError(pluginResp, it)) {
                return@launch
            } else {
                // joined and subscribed successfully, return the remote offer
                it.safeResume(pluginResp.jsep!!.sdp!!)
            }
        }

        it.invokeOnCancellation {
            isCancel = true
        }
    }

    suspend fun updateLocalOffer(offer: String) {
        // the websocket, session id, and handle id should be prepared
        // send an 'start' event to video room and post the local offer to remote server
        val startTransactionId = randomUUID()
        val startReq = createVideoRoomMessage(
            subscribeEndPoint.sessionId!!.toLong(), subscribeEndPoint.handleId!!.toLong(),
            startTransactionId
        )
        startReq["body"] = mapOf(
            Pair("request", "start")
        )
        startReq["jsep"] = mapOf(
            Pair("type", "answer"),
            Pair("sdp", offer)
        )

        val resp = sendMessageToServer(startTransactionId, startReq)
        val pluginResp = gson.fromJson(resp, JanusPluginResponse::class.java)

        if (isPluginRespError(pluginResp, null)) {
            throw Exception("Unable to upload local offer to server")
        }

        if ("ok" == pluginResp.plugindata?.data?.get("started")?.toString()) {
            // successfully upload local offer to remote server
        } else {
            throw Exception("Unhandled situation!!!")
        }
    }

    fun stopSubscribe() {
        webSocket?.let { socket ->
            // destroy the handle id for subscription
            val req = JanusRequest()
            req.janus = "detach"
            subscribeEndPoint.handleId?.apply {
                req.sessionId = subscribeEndPoint.sessionId!!.toLong()
                req.handleId = this.toLong()
                socket.send(gson.toJson(req))
            }

            // destroy the session id for subscription
            req.janus = "destroy"
            req.handleId = null

            subscribeEndPoint.sessionId?.apply {
                req.sessionId = this.toLong()
                socket.send(gson.toJson(req))
            }
        }
    }

    private fun stopPublish() {
        webSocket?.let { socket ->
            // destroy the handle ids
            val req = JanusRequest()
            req.janus = "detach"
            publishEndPoint.handleId?.apply {
                req.sessionId = publishEndPoint.sessionId!!.toLong()
                req.handleId = this.toLong()
                socket.send(gson.toJson(req))
            }

            // destroy the session ids
            req.janus = "destroy"
            req.handleId = null

            publishEndPoint.sessionId?.apply {
                req.sessionId = this.toLong()
                socket.send(gson.toJson(req))
            }
        }
    }

    fun destroy() {
        job?.cancel("Canceled by calling destroy")

        stopSubscribe()

        stopPublish()

        webSocket?.close(1000, "close the websocket actively")
        webSocket = null
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        super.onMessage(webSocket, text)

        Log.i(TAG, "Received text message from websocket: \n$text")

        handleMessage(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        super.onMessage(webSocket, bytes)

        val text = bytes.string(Charsets.UTF_8)
        Log.i(TAG, "Received byte message from websocket: \n$text")

        handleMessage(text)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        super.onClosed(webSocket, code, reason)
        Log.i(TAG, "The websocket is closed ($code}: $reason")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        super.onClosing(webSocket, code, reason)
        Log.i(TAG, "The websocket is closing ($code): $reason")
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        super.onFailure(webSocket, t, response)
        Log.e(TAG, "Unable to connect to the server through websocket", t)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        super.onOpen(webSocket, response)

        Log.i(TAG, "Connect to server through websocket successfully")

        continuation?.safeResume(webSocket)
        continuation = null
    }
}