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
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.AbstractMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.Pair
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "OfferExchanger"

private const val SUCCESS = "success"

private const val VIDEO_ROOM_PLUGIN = "janus.plugin.videoroom"

private const val iceServerUrl = "stun:stun.l.google.com:1930";

//private const val serverProtocolAndHost = "http://10.96.231.121"
private const val serverProtocolAndHost = "http://10.112.53.217"

private const val wsServerProtocolAndHostPort = "ws://10.112.53.217:8188"

private const val whipPort = 7080

private const val whepPort = 7090

private const val needProxy = false

private const val proxyHost = "10.112.53.217"

private const val proxyPort = 8888

private const val TIMEOUT_INTERVAL = 5000L

interface IOfferExchange {

    suspend fun exchangeOffer(localOffer: String): String

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

data class SimpleJanusResponse(var data: Map<String, Any?>?) : BaseJanusBean()

data class PluginData(
    var plugin: String? = null,
    var data: Map<String, Any?>? = null
)

data class Error(
    var code: Int = 0,
    var reason: String? = null
)

data class JSEPData(var type: String? = null, var sdp: String? = null)

data class JanusPluginResponse(
    var plugindata: PluginData? = null,
    var error: Error? = null,
    var jsep: JSEPData? = null
) : BaseJanusBean()

data class JanusRequest(
    @SerializedName("session_id") var sessionId: String? = null,
    @SerializedName("plugin") var plugin: String? = null,
    @SerializedName("handle_id") var handleId: String? = null,
) : BaseJanusBean()

interface TransactionFilter {

    fun isInvalidMsgForTransaction(result: Map<String, Any?>): Boolean

}

class WebSocketOfferExchange(
    val scope: CoroutineScope,
    val intervalToKeepAlive: Long,
    var headsetStatusCallBack: ((String) -> Unit)?
): WebSocketListener() {

    val TAG = "WebSocketOfferExchange"

    val FIELD_ID = "id"

    val ROOM_ID = 1234

    val ROOM_PIN = "adminpwd"

    val VIDEO_PUBLISHER_ID = "video_data_publisher"

    val VIDEO_PUBLISHER_DISPLAY = "DroneController"

    val DATA_PUBLISHER_ID = "data_publisher"

    val DATA_PUBLISHER_DISPLAY = "Headset"

    val waitingTransactions: AbstractMap<String, CancellableContinuation<String>?> =
        ConcurrentHashMap<String, CancellableContinuation<String>?>()

    val waitingTransactionsFilters: AbstractMap<String, TransactionFilter> = ConcurrentHashMap()

    var webSocket: WebSocket? = null

    var webSocketContinuation: CancellableContinuation<WebSocket>? = null

    lateinit var gson: Gson

    private val lock: Lock = ReentrantLock()

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
        webSocketContinuation = it
        okHttpClient.newWebSocket(request, this)
        okHttpClient.dispatcher.executorService.shutdown()

        gson = GsonBuilder().create()

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalToKeepAlive)

                if (null != webSocket) {
                    publishEndPoint.sessionId?.apply {
                        val keepAliveReq = JanusRequest("keepalive")
                        keepAliveReq.sessionId = this
                        webSocket!!.send(gson.toJson(keepAliveReq))
                    }

                    subscribeEndPoint.sessionId?.apply {
                        val keepAliveReq = JanusRequest("keepalive")
                        keepAliveReq.sessionId = this
                        webSocket!!.send(gson.toJson(keepAliveReq))
                    }
                }
            }
        }
        scope.launch (Dispatchers.IO) {
            delay(TIMEOUT_INTERVAL)

            webSocketContinuation?.apply {
                this.resumeWithException(TimeoutException("Connect to websocket timeout"))
                webSocketContinuation = null
            }

        }
        it.invokeOnCancellation {
            webSocketContinuation = null
        }
    }

    private fun handleMessage(msg: String) {
        val result = gson.fromJson(msg, object : TypeToken<Map<String, Any?>>() {})

        result["transaction"]?.toString()?.apply {
            if (waitingTransactionsFilters.get(this)?.isInvalidMsgForTransaction(result) == true) {
                return@apply
            }
            handleTransaction(this, msg, Exception())
        }

        val event = "event"

        if (event == result.get("janus")
            && VIDEO_ROOM_PLUGIN == (result.get("plugindata") as? Map<String, Any?>?)?.get("plugin")) {
            val data: Map<String, Any?>? = (result.get("plugindata") as? Map<String, Any?>)?.get("data") as? Map<String, Any?>?
            if (event == data?.get("videoroom") && ROOM_ID == data["room"]
                && DATA_PUBLISHER_ID == data["unpublished"]) {
                // the headset went offline
                headsetStatusCallBack?.invoke(EVENT_HEADSET_OFFLINE)
            }
        }
    }

    private fun handleTransaction(transaction: String, result: String?, exception: Exception) {
        val continuation: Continuation<String>? = waitingTransactions.remove(transaction)
        waitingTransactionsFilters.remove(transaction)

        continuation?.let {
            if (null != result) {
                continuation.resume(result)
            } else {
                continuation.resumeWithException(exception)
            }
            ""
        } ?: {
            // TODO handle the message without transaction id
        }
    }

    private suspend fun sendMessageToServer(transaction: String, req: Any): String =
        suspendCancellableCoroutine {
            scope.launch(Dispatchers.IO) {
                val text: String = gson.toJson(req)
                if (webSocket!!.send(text)) {
                    Log.d(TAG, "Add message to queue: ${text}")
                    delay(TIMEOUT_INTERVAL)

                    handleTransaction(
                        transaction, null,
                        TimeoutException("Hasn't received result in $TIMEOUT_INTERVAL milliseconds")
                    )
                } else {
                    it.resumeWithException(IOException("Failed to add data into queue in WebSocket"))
                }
            }
        }

    private suspend fun makeSureWebSocket(continuation: Continuation<String>) {
        if (null == webSocket) {
            lock.lock()
            try {
                // TODO can not use the lock right here, because of the coroutine
                if (null == webSocket) {
                    webSocket = initWebSocket()
                    // wait until the connection is built successfully
                }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            } finally {
                lock.unlock()
            }
        }
    }

    private suspend fun makeSureSessionAndHandleId(
        publish: Boolean,
        continuation: Continuation<String>
    ) {
        if ((publish && !publishEndPoint.isValid()) || (!publish && !subscribeEndPoint.isValid())) {
            lock.lock()
            try {
                if ((publish && TextUtils.isEmpty(publishEndPoint.sessionId))
                    || (!publish && TextUtils.isEmpty(subscribeEndPoint.sessionId))
                ) {
                    // make sure session is valid
                    val sessionResp = withContext(Dispatchers.IO) {
                        val req = BaseJanusBean("create")
                        sendMessageToServer(req.transaction!!, req)
                    }
                    // parse the result
                    var resp = gson.fromJson(sessionResp, SimpleJanusResponse::class.java)

                    if (SUCCESS != resp.janus || TextUtils.isEmpty(
                            resp.data?.get(FIELD_ID)?.toString()
                        )
                    ) {
                        // return error
                        continuation.resumeWithException(Exception("Unable to create a session"))
                        return
                    }

                    if (publish) {
                        publishEndPoint.sessionId = resp.data?.get(FIELD_ID)?.toString()!!
                    } else {
                        publishEndPoint.sessionId = resp.data?.get(FIELD_ID)?.toString()!!
                    }
                }
                val sessionId = if (publish) {
                    publishEndPoint.sessionId
                } else {
                    subscribeEndPoint.sessionId
                }

                // attach session to the video room
                val janusReq = JanusRequest(sessionId, plugin = VIDEO_ROOM_PLUGIN)
                janusReq.janus = "attach"

                val handleIdResp = withContext(Dispatchers.IO) {
                    sendMessageToServer(janusReq.transaction!!, janusReq)
                }
                val resp = gson.fromJson(handleIdResp, SimpleJanusResponse::class.java)

                if (SUCCESS != resp.janus || TextUtils.isEmpty(
                        resp.data?.get(FIELD_ID)?.toString()
                    )
                ) {
                    // return error
                    continuation.resumeWithException(Exception("Unable to attach a session to video room"))
                    return
                }

                if (publish) {
                    publishEndPoint.handleId = resp.data?.get(FIELD_ID)?.toString()
                } else {
                    subscribeEndPoint.handleId = resp.data?.get(FIELD_ID)?.toString()
                }
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            } finally {
                lock.unlock()
            }
        }
    }

    suspend fun startPublish(localOffer: String): String =
        suspendCancellableCoroutine {
            val transactionId = randomUUID()
            var isCancel = false
            scope.launch(Dispatchers.IO) {
                makeSureWebSocket(it)

                makeSureSessionAndHandleId(true, it)

                // TODO is handling the candidates mandatory


                // start publish video, audio and data, using the legacy way
                val publishReq = mapOf(
                    Pair("request", "joinandconfigure"),
                    Pair("session_id", publishEndPoint.sessionId),
                    Pair("handle_id", publishEndPoint.handleId),
                    Pair("transaction", transactionId),
                    Pair("jsep", localOffer),
                    Pair(
                        "body", mapOf<String, Any>(
                            Pair("room", ROOM_ID),
                            Pair("pin", ROOM_PIN),
                            Pair("ptype", "publisher"),
                            Pair("id", VIDEO_PUBLISHER_ID),
                            Pair("display", VIDEO_PUBLISHER_DISPLAY),
                            Pair("audio", true),
                            Pair("video", true),
                            Pair("data", true)
                        )
                    )
                )

                if (isCancel) {
                    return@launch
                }

                waitingTransactionsFilters[transactionId] = object : TransactionFilter {
                    override fun isInvalidMsgForTransaction(result: Map<String, Any?>): Boolean {
                        return "ack" == result["janus"]
                    }
                }

                var resp = sendMessageToServer(transactionId, publishReq)

                val pluginResp = gson.fromJson(resp, JanusPluginResponse::class.java)

                if ("event" == pluginResp.janus) {
                    // error
                    it.resumeWithException(Exception(pluginResp.error!!.reason))
                } else if (!TextUtils.isEmpty(
                        pluginResp.plugindata?.data?.contains("error")?.toString()
                    )
                ) {
                    it.resumeWithException(
                        Exception(
                            pluginResp.plugindata?.data?.get("error")?.toString()!!
                        )
                    )
                } else if (!TextUtils.isEmpty(
                        pluginResp.plugindata?.data?.get("reason")?.toString()
                    )
                ) {
                    it.resumeWithException(
                        Exception(
                            pluginResp.plugindata?.data?.get("reason")?.toString()!!
                        )
                    )
                } else {
                    // success

                    // optional, setting up forwarding
                    val forwardingTransactionId = randomUUID()
                    val forwardingReq = mapOf(
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
                    sendMessageToServer(forwardingTransactionId, forwardingReq)


                    // check if the headset is publishing data
                    if (pluginResp.plugindata?.data?.get("publishers") is Collection<*>) {
                        val publishers: Collection<*> =
                            pluginResp.plugindata?.data?.get("publishers") as Collection<*>

                        for (data in publishers) {
                            val publisher: Map<String, *>? = data as? Map<String, *>
                            if (DATA_PUBLISHER_ID == publisher?.get(FIELD_ID)) {
                                // the headset is publishing data
                                headsetStatusCallBack?.invoke(EVENT_HEADSET_ONLINE)
                                break
                            }
                        }
                    }

                    it.resume(pluginResp.jsep?.sdp!!)
                }
            }
            it.invokeOnCancellation {
                isCancel = true
            }
        }


    suspend fun startSubscribe(localOffer: String): String {
        return ""
    }

    suspend fun destroy() {
        job?.cancel("Canceled by calling destroy")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        super.onMessage(webSocket, text)

        Log.i(TAG, "Received text message from websocket: $text")

        handleMessage(text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        super.onMessage(webSocket, bytes)

        val text = bytes.string(Charsets.UTF_8)
        Log.i(TAG, "Received byte message from websocket: $text")

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
        response: Response?
    ) {
        super.onFailure(webSocket, t, response)
        Log.e(TAG, "Unable to connect to the server through websocket", t)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        super.onOpen(webSocket, response)

        Log.i(TAG, "Connect to server through websocket successfully")

        if (null != webSocketContinuation) {
            webSocketContinuation!!.resume(webSocket)
        } else {
            webSocket.close(0, "useless websocket")
        }
    }
}


abstract class HttpOfferExchange(val url: String) : IOfferExchange {

    var retrofit: Retrofit

    init {
        val client = OkHttpClient.Builder().apply {
            if (needProxy) {
                this.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort)))
            }
        }.build()
        retrofit = Retrofit.Builder()
            .baseUrl(serverProtocolAndHost)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
    }

    override suspend fun exchangeOffer(localOffer: String): String {
        val requestBody =
            localOffer.encodeToByteArray().toRequestBody("application/sdp".toMediaType())

        return retrofit.create(IOfferRequest::class.java).exchangeOffer(url, requestBody).string()
    }

    override suspend fun destroy() {
    }
}

class WhipOfferExchanger(private val endPoint: String) :
    HttpOfferExchange("$serverProtocolAndHost:$whipPort/whip/endpoint/$endPoint") {

    override suspend fun exchangeOffer(localOffer: String): String {
        // destroy old endpoint first
        destroy()

        // create a new whip endpoint
        retrofit.create(IOfferRequest::class.java).createEndPoint(
            "$serverProtocolAndHost:$whipPort/whip/create",
            newWhipEndPointParameters()
        )


        return super.exchangeOffer(localOffer)
    }

    override suspend fun destroy() {
        try {
            retrofit.create(IOfferRequest::class.java).destroyEndPoint(url)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy the old whip endpoint: $endPoint")
        }
    }

    private fun newWhipEndPointParameters(): Map<String, Any> {
        val body = hashMapOf(
            Pair("id", endPoint),
            // INFO the room number must be same as the video room number configured in the janus video room configuration file
            Pair("room", 1234),
            Pair("secret", "adminpwd"),
            Pair(
                "recipient", hashMapOf(
                    Pair("host", "127.0.0.1"),
                    Pair("audioPort", 5002),
                    Pair("audioRtcPort", 5003),
                    Pair("videoPort", 5004),
                    Pair("videoRtcPort", 5005),
                    Pair("dataPort", 5006)
                )
            )
        )
        return body
    }
}

class WhepOfferExchanger(private val endPoint: String) :
    HttpOfferExchange("$serverProtocolAndHost:$whepPort/whep/endpoint/$endPoint") {

    override suspend fun exchangeOffer(localOffer: String): String {
        destroy()

        // create a new whep endpoint
        retrofit.create(IOfferRequest::class.java).createEndPoint(
            "$serverProtocolAndHost:$whepPort/whep/create",
            newWhepEndPointParamters()
        )

        return super.exchangeOffer(localOffer)
    }

    override suspend fun destroy() {
        try {
            retrofit.create(IOfferRequest::class.java).destroyEndPoint(url)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy the old whep endpoint: $endPoint")
        }
    }

    private fun newWhepEndPointParamters(): Map<String, Any> {
        val body = hashMapOf(
            Pair("id", this.endPoint),
            // the mountpoint must be same as the streaming room id configured in the janus streaming configuration file
            Pair("mountpoint", 1),
            Pair(
                "iceServers", listOf(
                    hashMapOf(
                        Pair("uri", iceServerUrl)
                    )
                )
            )
        )

        return body
    }
}

interface IOfferRequest {

    @POST
    suspend fun exchangeOffer(@Url url: String, @Body offer: RequestBody): ResponseBody

    // already add gson converter to the retrofit,
    // all type except RequestBody for body will be converted to json
    @POST
    suspend fun createEndPoint(@Url url: String, @Body body: Any): ResponseBody

    @DELETE
    suspend fun destroyEndPoint(@Url url: String): ResponseBody


}