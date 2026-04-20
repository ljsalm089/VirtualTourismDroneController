package dji.sampleV5.aircraft.media

import android.os.SystemClock
import dji.sampleV5.aircraft.DJIApplication
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VideoManager private constructor(val scope: CoroutineScope, dispatcher: CoroutineDispatcher) :
    ICameraStreamManager.CameraFrameListener {

        private var startTimestamp: Long = 0

    var dispatching: CoroutineDispatcher =
        dispatcher.limitedParallelism(1, "Dispatching VideoManager")

    private var subscribers: MutableList<VideoFrameListener> = mutableListOf()

    private fun startVideoSubscription() {
        startTimestamp = 0
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(ComponentIndexType.LEFT_OR_MAIN,
            ICameraStreamManager.FrameFormat.YUV420_888, this)
    }

    private fun stopVideoSubscription() {
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(this)
    }

    fun subscribe(listener: VideoFrameListener) {
        scope.launch(dispatching) {
            subscribers.add(listener)

            if (subscribers.size == 1) {
                startVideoSubscription()
            }
        }
    }

    fun unsubscribe(listener: VideoFrameListener) {
        scope.launch(dispatching) {
            subscribers.remove(listener)

            if (subscribers.isEmpty()) {
                stopVideoSubscription()
            }
        }
    }


    override fun onFrame(
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        format: ICameraStreamManager.FrameFormat
    ) {
        scope.launch(dispatching) {
            if (0L == startTimestamp) {
                startTimestamp = SystemClock.elapsedRealtime()
            }
            val frame = VideoFrame(frameData, length - offset, width, height, format.value,
                (SystemClock.elapsedRealtime() - startTimestamp) / 1000.0)
            frame.reference()
            try {
                for (listener in subscribers) {
                    listener.onVideoFrame(frame)
                }
            } finally {
                frame.release()
            }
        }
    }

    companion object {

        val instance: VideoManager = VideoManager(DJIApplication.applicationScope, Dispatchers.IO)

    }
}

fun interface VideoFrameListener {

    fun onVideoFrame(frame: VideoFrame)

}