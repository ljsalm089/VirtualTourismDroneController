package dji.sampleV5.aircraft

//webrtc things imports
//import org.otago.hci.videosource.android.databinding.ActivityMainBinding //unresolved reference: otago
import android.Manifest
import android.R.attr
import android.app.Activity
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.PermissionChecker
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
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
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrame.I420Buffer
import org.webrtc.VideoSource
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit


val permissions = listOf(Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.ACCESS_NETWORK_STATE)

//val TAG = "MainActivity"

// the url to publish the video
val videoUrl = "http://192.168.0.136:7080/whip/endpoint/test"

val needProxy = false


class CameraStreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var surface: Surface? = null // how we draw the camera stream
    private val cameraStreamManager: ICameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager // we need the camera manager to use camera functions
    private val cameraIndex = ComponentIndexType.LEFT_OR_MAIN // which camera we end up using MAY NEED TO TWEAK IF I HAVE THE WRONG CAM
    private var pipe1: String = ""
    var frameWriter: FileOutputStream? = null
    var f: File? = null

    lateinit var videoSource: VideoSource

    val TAG = "DebugTag"

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
            Log.d("CameraStream", "OnFrame of framelistener is called")
            Log.d("CameraStream", "Frame data size: " + frameData.size + " - (first 10 bytes): ${frameData.take(10).joinToString("") { "%02x".format(it) }}") // want to see the ByteArray
            //modifyGreenChannel(frameData, offset, width, height) // we will uncomment later when we know the stream works
            Log.d("CameraStream", "")
            // draws the frame into the SurfaceView
            drawFrameOnSurface(frameData, offset, width, height) //will uncomment when written the method
//            testingPipe()
        }
    }

    //webrtc stuff

    private lateinit var peerConnectionFactory: PeerConnectionFactory

    private lateinit var peerConnection: PeerConnection

    private val eglBase = EglBase.create()

    private lateinit var retrofit: Retrofit



    override fun onCreate(savedInstanceState: Bundle?) { //
//        Log.d("CameraStream", "On Create started for CameraStreamActivity")
        super.onCreate(savedInstanceState)
        binding = ActivityCameraStreamBinding.inflate(layoutInflater)
        setContentView(R.layout.activity_camera_stream)
        //allows event handling when stuff happens to the surface view such as is created, changed and destroyed. (every frame of teh camera will change it)
        binding.surfaceView.holder.addCallback(this)
//        Log.d("CameraStream", "On Create fully ran for CameraStreamActivity")

        //jiasheng code
        val client = OkHttpClient.Builder().apply {
            if (needProxy) {
                this.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("192.168.1.7", 8888)))
            }
        }.build()
        retrofit = Retrofit.Builder()
            .baseUrl("http://192.168.0.136:7080/")
            .client(client)
            .build()

        initializeWebRTC()

    }

    override fun surfaceCreated(holder: SurfaceHolder) {
//        Log.d("CameraStream", "surfaceCreated method started")
        surface = holder.surface
        // here we start listening to the incoming frames
        cameraStreamManager.addFrameListener(
            cameraIndex,
            ICameraStreamManager.FrameFormat.NV21,
            frameListener
        )

//        Call here to start FFmpeg session
        //startFFmpegSession();

//        Log.d("CameraStream", "surfaceCreated method over")
    }

    private fun startFFmpegSession (){
        //Trying a basic call to FFmpeg to ensure it is here
        pipe1 = FFmpegKitConfig.registerNewFFmpegPipe(this)
        Log.d(TAG, "A pipe looks like " + pipe1)


//        val ffmpegCommand = "-i" + pipe1 // + whip stuff
//        val session = FFmpegKit.execute(ffmpegCommand)

        var outputFile = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "file2.mp4")
        val rtmp_url = "rtp://10.96.95.54:1935/cam"

//        val session = FFmpegKit.executeAsync("-re -f rawvideo -pixel_format argb32 -video_size 640x480 -i " + pipe1 + " -f mpeg4 " + outputFile.absolutePath, FFmpegSessionCompleteCallback
        val session = FFmpegKit.executeAsync("-re -f rawvideo -pixel_format nv21 -video_size 640x480 -i " + pipe1 + " -f rtp_mpegts " + rtmp_url, FFmpegSessionCompleteCallback
                { session ->
                    val state = session.state
                    val returnCode = session.returnCode
                    // CALLED WHEN SESSION IS EXECUTED
                    Log.d(
                        TAG,
                        String.format(
                            "FFmpeg process exited with state %s and rc %s.%s",
                            state,
                            returnCode,
                            session.failStackTrace
                        )
                    )
                }, {
                    // CALLED WHEN SESSION PRINTS LOGS
                }, {
                    // CALLED WHEN SESSION GENERATES STATISTICS
                })
        // the session is currently failing - why though? is the command wrong?
//        f = File(pipe1)
//        frameWriter = FileOutputStream(f)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        //if we need to handle rotations and other stuff
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // clean up when the surface is destroyed
        surface = null
        cameraStreamManager.removeFrameListener(frameListener)
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
        runOnUiThread {
            surface?.let { surface ->
                try {
//                    Log.d("CameraStream", "Made it to the try loop of the drawFrameOnSurface")
//                    Log.d("CameraStream", "Frame data: ${frameData.joinToString("") { "%02x".format(it) }}") // want to see the ByteArray
                    //val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//                    Log.d("CameraStream", "bitmap val set up")
//                    val buffer = ByteBuffer.wrap(frameData, offset, width * height * 4) // trying old way and commenting out the first way - post test (this one makes us error earlier with null error) - this one is baad dont use!!!!
                    //val buffer = ByteBuffer.wrap(frameData, offset, frameData.size) // again how does this offset work? although it seems like they want the offset seperatly so maybe it is right?
//                    Log.d("CameraStream", "buffer val created")
                    //bitmap.copyPixelsFromBuffer(buffer) //currently getting an error here - Buffer not large enough for pixels - offset problem?
//                    Log.d("CameraStream", "bitmap copied from buffer")

                    //val canvas = surface.lockCanvas(null) //what is the canvas for and what does this do  - we lock the canvas to be able to draw on it and later unlock it
//                    Log.d("CameraStream", "canva setup")
                    //canvas.drawBitmap(bitmap, 0f, 0f, null)
//                    Log.d("CameraStream", "bitmap drawn on canvas")
                    //surface.unlockCanvasAndPost(canvas)
//                    Log.d("CameraStream", "canvas unlocked")
                    //bitmap.recycle() //free the memory
//                    Log.d("CameraStream", "bitmap freed")

                    //send frames here?
//                    writeToPipe(frameData)

                    //send frames jaisheng way
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
                    videoSource.capturerObserver?.onFrameCaptured(videoFrame)
                    Log.d(TAG, "sent frame to videoSource")
                    videoFrame.release()
                    Log.d(TAG, "release videoFrame")


                } catch (e: Exception) {
                    Log.e("CameraStream", "Failed to draw frame/set videoFrame: ${e.message}")
                }
            } ?: Log.e("CameraStream", "Surface is null") // elvis operator! this happens if the whole surface?.let thing doesnt work
        }
    }

    private fun writeToPipe(frameData: ByteArray){
        if (pipe1.isNotEmpty()) {
            Log.d(TAG, "pipe not empty")

            Log.d(TAG, "set frameWriter to output to file")
            frameWriter?.write(frameData, 0, frameData.size)
            Log.d(TAG, "wrote framedata to file")
            Log.d(TAG, frameData.toString())
        }
//        FFmpegKit.executeAsync("-re -f rawvideo -pixel_format argb32 -video_size 640x480 -i " + pipe1 + " -f rtp_mpegts" + " " + rtmp_url, new FFmpegSessionCompleteCallback() {
    }


    override fun onDestroy() {
        super.onDestroy()
        // Cleanup
        cameraStreamManager.removeFrameListener(frameListener)
    }

    override fun onPause() {
        super.onPause()
        frameWriter?.close()
    }

    fun testingPipe() {
        var img: ByteArray = ByteArray(640 * 480 * 4)   // dummy image
        for (i in 3..img.size step 4) {
            img[i] = 0xff.toByte()
        }
        var out: FileOutputStream = FileOutputStream(pipe1)
        try {
            for (i in 0..100) { // write 100 empty frames
                out.write(img);
            }
        } catch (e: Exception) {
            e.printStackTrace();
        } finally {
            out.close();
        }
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

    // commenting this out since I get an error saying onRequested permission result overrides nothing

//    override fun onRequestPermissionsResult(
//        requestCode: Int,
//        permissions: Array<out String>,
//        grantResults: IntArray,
//        deviceId: Int
//    ) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
//        if (requestCode == 100) {
//            if (hasPermission()) {
//                // continue
//                initializeWebRTC()
//            } else {
//                showToast("No enough permissions")
//            }
//        }
//    }

    private fun initializeWebRTC() {
        var option: PeerConnectionFactory.InitializationOptions = PeerConnectionFactory.InitializationOptions.builder(application)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(option)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        // TODO write custom VideoCapturer to push the video to the server
        val videoCapturer = DJIVideoCapturer()
        //val videoCapturer: VideoCapturer? = createCameraCapturer(Camera2Enumerator(this))
        if (null == videoCapturer) {
            Log.e(TAG, "unable to create the video capturer!")
            return
        }
        videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer.initialize(
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext),
            applicationContext,
            videoSource.capturerObserver
        )
        videoCapturer.startCapture(1280, 720, 30)
        val localVideoTrack = peerConnectionFactory.createVideoTrack("videoTrack", videoSource)

        val iceServers: MutableList<IceServer> = ArrayList()
        //iceServers.add(IceServer("stun:stun.l.google.com:19302"))

        val rtcConfig = RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val connection =
            peerConnectionFactory.createPeerConnection(rtcConfig, object : Observer {
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                }

                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "ICE Connection State: $p0")
                }

                override fun onIceConnectionReceivingChange(p0: Boolean) {
                }

                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                }

                override fun onIceCandidate(p0: IceCandidate?) {
                    Log.d(TAG, "ICE Candidate: ${p0?.sdp}")
                }

                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
                }

                override fun onAddStream(p0: MediaStream?) {
                }

                override fun onRemoveStream(p0: MediaStream?) {
                }

                override fun onDataChannel(p0: DataChannel?) {
                }

                override fun onRenegotiationNeeded() {
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
                }

                override fun onCreateFailure(p0: String?) {
                }

                override fun onSetFailure(p0: String?) {
                }

            }, MediaConstraints())

        }
    }

    private fun postSdpToServer(p: SessionDescription) {
        lifecycleScope.launch {
            try {
                val requestBody = p.description.encodeToByteArray().toRequestBody("application/sdp".toMediaType())
                val result = retrofit.create(IRequest::class.java).postSdp(videoUrl, requestBody).string()
                launch(Dispatchers.Main) {
                    peerConnection.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {
                        }

                        override fun onSetSuccess() {
                        }

                        override fun onCreateFailure(p0: String?) {
                        }

                        override fun onSetFailure(p0: String?) {
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

    private fun createCameraCapturer(enumerator: Camera2Enumerator) : CameraVideoCapturer? {
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

    @POST
    suspend fun postSdp(@Url url: String,  @Body sdp: RequestBody): ResponseBody

}