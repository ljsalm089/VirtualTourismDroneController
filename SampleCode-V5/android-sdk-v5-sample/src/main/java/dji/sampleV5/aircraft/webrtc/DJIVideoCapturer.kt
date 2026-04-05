package dji.sampleV5.aircraft.webrtc

import android.content.Context
import android.os.SystemClock
import dji.sampleV5.aircraft.media.VideoFrameListener
import dji.sampleV5.aircraft.media.VideoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import timber.log.Timber
import java.util.LinkedList
import kotlin.math.abs

class DJIVideoCapturer(private val scope: CoroutineScope) : VideoCapturer {

    private val queue = LinkedList<Long>()

    private val frameListener = VideoFrameListener { frame ->
        frame.reference()
        //            // the calling thread of this method in unknown, need to be careful
        currentVideoTimeInNanoSeconds = SystemClock.elapsedRealtimeNanos() - startCaptureTimeNS

        // feed the video data to webRtc
        val timestamp = SystemClock.elapsedRealtime()
        queue.addFirst(timestamp)
        pushVideoToServer(frame, currentVideoTimeInNanoSeconds)

        var gap: Long = timestamp
        do {
            var last = try {
                queue.last
            } catch (_: NoSuchElementException) {
                timestamp
            }
            gap = abs(last - timestamp)
            if (gap >= 1000L) {
                queue.removeLast()
            }
        } while (gap >= 1000L)
    }

    private var isCapturing = false

    private var isDisposed = false

    private lateinit var capturerObserver: CapturerObserver

    private var startCaptureTimeNS = SystemClock.elapsedRealtimeNanos()

    // allowing other component map complementary data to video frame and send to the other end through other channel
    var currentVideoTimeInNanoSeconds: Long = 0
        private set

    fun fetchFrameRate(): Int = queue.size

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?,
    ) {
        this.capturerObserver = capturerObserver!!
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        checkNotDisposed()
        scope.launch(Dispatchers.Main) {
            isCapturing = true

            startCaptureTimeNS = SystemClock.elapsedRealtimeNanos()

            VideoManager.instance.subscribe(frameListener)
        }
    }

    override fun stopCapture() {
        checkNotDisposed()
        scope.launch(Dispatchers.Main) {
            isCapturing = false

            VideoManager.instance.unsubscribe(frameListener)
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {

    }

    override fun dispose() {
        if (isCapturing) stopCapture()

        isDisposed = true
    }

    override fun isScreencast(): Boolean {
        return false
    }

    private fun checkNotDisposed() {
        if (isDisposed) throw RuntimeException("The capturer is disposed.")
    }

    private fun pushVideoToServer(
        frame: dji.sampleV5.aircraft.media.VideoFrame,
        videoTimeStampInNanoSeconds: Long
    ) {
        scope.launch(Dispatchers.IO) {
            if (isDisposed || !isCapturing) {
                frame.release()
                return@launch
            }

            try {
                val chromaHeight = (frame.height + 1) / 2
                val strideUV = (frame.width + 1) / 2
                val yPos = 0
                val uPos = yPos + frame.width * frame.height
                val vPos = uPos + strideUV * chromaHeight
                val buffer = frame.buffer
                buffer.slice()
                buffer.position(yPos)
                buffer.limit(uPos)
                val dataY = buffer.slice()
                buffer.position(uPos)
                buffer.limit(vPos)
                val dataU = buffer.slice()
                buffer.position(vPos)
                buffer.limit(vPos + strideUV * chromaHeight)
                val dataV = buffer.slice()
                val yuv420Buffer = JavaI420Buffer.wrap(
                    frame.width,
                    frame.height,
                    dataY,
                    frame.width,
                    dataU,
                    strideUV,
                    dataV,
                    strideUV
                ) {}

                val videoFrame =
                    VideoFrame(yuv420Buffer, 0, videoTimeStampInNanoSeconds)
                capturerObserver.onFrameCaptured(videoFrame)

            } catch (e: Exception) {
                Timber.e(e, "Failed to draw frame/set videoFrame")
            } finally {
                frame.release()
            }
        }
    }

}