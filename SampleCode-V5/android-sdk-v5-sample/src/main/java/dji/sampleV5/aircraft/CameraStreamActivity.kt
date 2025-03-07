package dji.sampleV5.aircraft

//webrtc things imports
//import org.otago.hci.videosource.android.databinding.ActivityMainBinding //unresolved reference: otago
import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.PermissionChecker
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import dji.sampleV5.aircraft.databinding.ActivityCameraStreamBinding
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.NV21Buffer
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.Observer
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit


val permissions = listOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_NETWORK_STATE
)

//val TAG = "MainActivity"

val baseUrl = "http://10.96.231.121:7080/"

val needProxy = false

val proxtHost = "192.168.142.127"
val proxyPort = 8888

val endPoint = "test"

class CameraStreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var surface: Surface? = null

    private val cameraIndex =
        ComponentIndexType.LEFT_OR_MAIN // which camera we end up using MAY NEED TO TWEAK IF I HAVE THE WRONG CAM

    var frameWriter: FileOutputStream? = null

    lateinit var videoSource: VideoSource

    val TAG = "CameraStreamActivity"

    private lateinit var binding: ActivityCameraStreamBinding

    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat
        ) {
            Log.d(
                "CameraStream",
                "Frame data size: " + frameData.size + " - (first 10 bytes): ${
                    frameData.take(10).joinToString("") { "%02x".format(it) }
                }"
            )
            modifyGreenChannel(frameData, offset, width, height)
            Log.d("CameraStream", "")
            // draws the frame into the SurfaceView
            drawFrameOnSurface(
                frameData,
                offset,
                width,
                height
            )
        }
    }


    private lateinit var peerConnectionFactory: PeerConnectionFactory

    private lateinit var peerConnection: PeerConnection

    private lateinit var capturer: DJIVideoCapturer

    private val eglBase = EglBase.create()

    private lateinit var retrofit: Retrofit


    override fun onCreate(savedInstanceState: Bundle?) { //
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            publish()
        }

        binding.btnEnd.setOnClickListener {
            stopPublishing()
        }


        //allows event handling when stuff happens to the surface view such as is created, changed and destroyed. (every frame of teh camera will change it)
        binding.surfaceView.holder.addCallback(this)

        val client = OkHttpClient.Builder().apply {
            if (needProxy) {
                this.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxtHost, proxyPort)))
            }
        }.build()
        retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .client(client)
            .build()

    }

    private fun publish() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = hashMapOf(
                    Pair("id", endPoint),
                    Pair("room", 1234),
                    Pair("secret", "adminpwd"),
                    Pair("recipient", hashMapOf(
                        Pair("host", "127.0.0.1"),
                        Pair("audioPort", 5002),
                        Pair("audioRtcPort", 5003),
                        Pair("videoPort", 5004),
                        Pair("videoRtcPort", 5005),
                        Pair("dataport", 5006)
                    ))
                )
                retrofit.create(IRequest::class.java).createEndPoint(body)
                launch(Dispatchers.Main) {
                    initializeWebRTC()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to create the endpoint: $e")
                showMessage("Failed to create the endpoint")
            }
        }
    }

    private fun stopPublishing() {
        if (this::capturer.isInitialized) {
            capturer.dispose()
        }
        if (this::peerConnection.isInitialized) {
            peerConnection.dispose()
        }
        if (this::peerConnectionFactory.isInitialized) {
            peerConnectionFactory.dispose()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                retrofit.create(IRequest::class.java).destroyEndPoint(endPoint)
                showMessage("Destroy endpoint successfully")
            } catch (e: Exception) {
                Log.d(TAG, "Failed to destroy the endpoint: $e")
                showMessage("Failed to destroy the endpoint")
            }
        }
    }

    private fun showMessage(msg: String) {
        lifecycleScope.launch (Dispatchers.Main) {
            showToast(msg)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            cameraIndex,
            ICameraStreamManager.FrameFormat.NV21,
            frameListener
        )

    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        //if we need to handle rotations and other stuff
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // clean up when the surface is destroyed
        surface = null
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
        Log.d("CameraStream", "frameListener removed")
    }

    private fun modifyGreenChannel(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        Log.d("CameraStream", "First line of modifyGreenChannel method")

        // Validate parameters first
        if (offset < 0) {
            Log.e("CameraStream", "Invalid negative offset: $offset")
            return
        }

        // Add counter for log throttling
        var iterationCount = 0

        // Process pixels assuming 4-byte format (e.g., RGBA)
        // Now starts from 0 instead of offset, with step=4
        for (i in 0 until (frameData.size - 1) step 4) {
            // Only log every 200,000 iterations
            if (iterationCount++ % 200000 == 0) {
                Log.d("CameraStream", "Processing iteration $iterationCount")
                Log.d("CameraStream", "i: $i")
            }

            // Safety check for array bounds
            if (i + 1 >= frameData.size) {
                Log.w("CameraStream", "Index out of bounds at i: $i")
                break
            }

            // Get and modify green channel
            val greenValue = frameData[i + 1]
            val newGreen = (greenValue + 20).coerceAtMost(0xFE).toByte()

            if (iterationCount % 200000 == 0) {
                Log.d("CameraStream", "Original green: $greenValue, New green: $newGreen")
            }

            frameData[i + 1] = newGreen
        }

        Log.d("CameraStream", "Completed processing. Total iterations: $iterationCount")
    }

    private fun drawFrameOnSurface(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        if (!this::capturer.isInitialized || !capturer.isCapturing || null == capturer.capturerObserver) return

        surface?.let { surface ->
            try {
                Log.d(TAG, "starting the videoFrame making")
                val nv21Buffer: NV21Buffer = NV21Buffer(frameData, width, height, null)
                //ConversionUtils.convertARGB32ToI420(frameData, attr.width, attr.height)
                Log.d(TAG, "buffer created: ")
                val timestampNS: Long =
                    TimeUnit.MILLISECONDS.toNanos(SystemClock.elapsedRealtime())
                Log.d(TAG, "timestamp: $timestampNS")
                //val videoFrame = VideoFrame(i420Buffer, 0, timestampNS)
                val videoFrame = VideoFrame(nv21Buffer, 0, timestampNS)
                Log.d(TAG, "videoFrame made")
                capturer.capturerObserver?.onFrameCaptured(videoFrame)
                Log.d(TAG, "sent frame to videoSource")
                videoFrame.release()
                Log.d(TAG, "release videoFrame")
            } catch (e: Exception) {
                Log.e("CameraStream", "Failed to draw frame/set videoFrame: ${e.message}")
            }
        } ?: Log.e(
            "CameraStream",
            "Surface is null"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
    }

    override fun onPause() {
        super.onPause()
        frameWriter?.close()
    }


    //webRTC and co from Jason down here
    private fun hasPermission(): Boolean {
        for (permission in permissions) {
            if (PermissionChecker.checkSelfPermission(this, permission) != PERMISSION_GRANTED) {
                return false;
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (hasPermission()) {
                // continue
                initializeWebRTC()
            } else {
                showToast("No enough permissions")
            }
        }
    }

    private fun initializeWebRTC() {
        var option: PeerConnectionFactory.InitializationOptions =
            PeerConnectionFactory.InitializationOptions.builder(application)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        PeerConnectionFactory.initialize(option)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        // TODO write custom VideoCapturer to push the video to the server
        capturer = DJIVideoCapturer()

        videoSource = peerConnectionFactory.createVideoSource(capturer.isScreencast)
        capturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            applicationContext,
            videoSource.capturerObserver
        )
        capturer.startCapture(1280, 720, 30)
        val localVideoTrack = peerConnectionFactory.createVideoTrack("videoTrack", videoSource)

        val iceServers: MutableList<IceServer> = ArrayList()
        iceServers.add(IceServer("stun:stun.l.google.com:19302"))

        val rtcConfig = RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val connection =
            peerConnectionFactory.createPeerConnection(rtcConfig, object : Observer {
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
                }

                override fun onRenegotiationNeeded() {
                    Log.d(TAG, "onRenogotiationNeeded")
                }
            })
        if (null == connection) {
            showToast("Create peer connection error")
            return
        }
        this.peerConnection = connection
        this.peerConnection.let {
            val mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream")
            mediaStream.addTrack(localVideoTrack)
            peerConnection.addTrack(localVideoTrack, listOf("streamId"))

            peerConnection.createOffer(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {
                    p0?.let {
                        peerConnection.setLocalDescription(this, p0)

                        postSdpToServer(p0)
                        return
                    }

                    showToast("Fail to create sdp")
                }

                override fun onSetSuccess() {
                    Log.d(TAG, "set local sdp successfully")
                }

                override fun onCreateFailure(p0: String?) {
                    Log.d(TAG, "failed to create local sdp: $p0")
                }

                override fun onSetFailure(p0: String?) {
                    Log.d(TAG, "failed to set local sdp: $p0")
                }

            }, MediaConstraints())

        }
    }

    private fun postSdpToServer(p: SessionDescription) {
        lifecycleScope.launch {
            try {
                val requestBody =
                    p.description.encodeToByteArray().toRequestBody("application/sdp".toMediaType())
                val result =
                    retrofit.create(IRequest::class.java).postSdp(endPoint, requestBody).string()
                launch(Dispatchers.Main) {
                    peerConnection.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {
                        }

                        override fun onSetSuccess() {
                            Log.d(TAG, "set remote sdp successfully")
                        }

                        override fun onCreateFailure(p0: String?) {
                        }

                        override fun onSetFailure(p0: String?) {
                            Log.d(TAG, "failed to set remote sdp: $p0")
                        }

                    }, SessionDescription(SessionDescription.Type.ANSWER, result))
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Log.e(TAG, "Post sdp to server fail: $e")
                    showToast("Post sdp to server fail: ${e.message}")
                }
            }
        }
    }

    private fun createCameraCapturer(enumerator: Camera2Enumerator): CameraVideoCapturer? {
        val deviceNames = enumerator.deviceNames

        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                var capturer = enumerator.createCapturer(deviceName, null)
                if (null != capturer) {
                    return capturer
                }
            }
        }
        return null
    }
}

fun Activity.showToast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

interface IRequest {

    @POST("/whip/endpoint/{endpoint}")
    suspend fun postSdp(@Path("endpoint") endpoint: String, @Body sdp: RequestBody): ResponseBody

    @POST("/whip/create")
    suspend fun createEndPoint(@Body body: Any): ResponseBody

    @DELETE("/whip/endpoint/{endpoint}")
    suspend fun destroyEndPoint(@Path("endpoint") endpoint: String): ResponseBody

}