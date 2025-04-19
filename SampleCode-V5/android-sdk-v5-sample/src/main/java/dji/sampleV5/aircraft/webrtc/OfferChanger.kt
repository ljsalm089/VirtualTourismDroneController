package dji.sampleV5.aircraft.webrtc

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Url
import java.net.InetSocketAddress
import java.net.Proxy

private const val TAG = "OfferExchanger"

private const val iceServerUrl = "stun:stun.l.google.com:1930";

private const val serverProtocolAndHost = "http://192.168.1.16"

private const val whipPort = 7080

private const val whepPort = 7090

private const val needProxy = true

private const val proxyHost = "192.168.1.16"

private const val proxyPort = 8888


interface IOfferExchange {

    suspend fun exchangeOffer(localOffer: String): String

    suspend fun destroy()
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