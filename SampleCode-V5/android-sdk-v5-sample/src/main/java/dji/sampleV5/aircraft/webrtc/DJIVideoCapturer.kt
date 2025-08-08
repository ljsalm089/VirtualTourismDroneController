package dji.sampleV5.aircraft.webrtc

import android.content.Context
import android.os.SystemClock
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.LinkedList

class DJIVideoCapturer(private val scope: CoroutineScope) : VideoCapturer {


    private val queue = LinkedList<Long>()

    private val frameListener =
        ICameraStreamManager.CameraFrameListener { frameData, offset, length, width, height, _ ->
            // feed the video data to webRtc
            val timestamp = SystemClock.elapsedRealtime()
            queue.addFirst(timestamp)
            pushVideoToServer(frameData, offset, length, width, height)

            var gap: Long = 0
            do {
                var last = try {
                    queue.last
                } catch (_: NoSuchElementException) {
                    null
                }
                gap = (last?: 0) - timestamp
                if (gap >= 1000L) {
                    queue.removeLast()
                }
            } while (gap >= 1000L)
        }

    private var isCapturing = false

    private var isDisposed = false

    private lateinit var capturerObserver: CapturerObserver

    private var startCaptureTimeNS = System.nanoTime()

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

            startCaptureTimeNS = System.nanoTime()

            MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
                ComponentIndexType.LEFT_OR_MAIN,
                ICameraStreamManager.FrameFormat.YUV420_888,
                frameListener
            )
        }
    }

    override fun stopCapture() {
        checkNotDisposed()
        scope.launch(Dispatchers.Main) {
            isCapturing = false

            MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {

    }

    override fun dispose() {
        isDisposed = true
    }

    override fun isScreencast(): Boolean {
        return false
    }

    private fun checkNotDisposed() {
        if (isDisposed) throw RuntimeException("The capturer is disposed.")
    }

    private fun pushVideoToServer(
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
    ) {
        scope.launch(Dispatchers.IO) {
            if (isDisposed || !isCapturing) {
                return@launch
            }

            pushVideoToServerAsynchronously(height, width, length, offset, frameData)
        }
    }

    private fun pushVideoToServerAsynchronously(
        height: Int,
        width: Int,
        length: Int,
        offset: Int,
        frameData: ByteArray,
    ) {
        try {
            val chromaHeight = (height + 1) / 2
            val strideUV = (width + 1) / 2
            val yPos = 0
            val uPos = yPos + width * height
            val vPos = uPos + strideUV * chromaHeight
            val buffer = ByteBuffer.allocateDirect(length - offset)
            buffer.put(frameData, offset, length)
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
                width,
                height,
                dataY,
                width,
                dataU,
                strideUV,
                dataV,
                strideUV
            ) {}

            val videoFrame =
                VideoFrame(yuv420Buffer, 0, System.nanoTime() - startCaptureTimeNS)
            capturerObserver.onFrameCaptured(videoFrame)
            videoFrame.release()
        } catch (e: Exception) {
            Timber.e(e, "Failed to draw frame/set videoFrame")
        }
    }


}