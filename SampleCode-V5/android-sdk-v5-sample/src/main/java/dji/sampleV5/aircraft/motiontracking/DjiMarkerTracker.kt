package dji.sampleV5.aircraft.motiontracking

import dji.sampleV5.aircraft.media.VideoFrame
import dji.sampleV5.aircraft.media.VideoFrameListener
import dji.sampleV5.aircraft.media.VideoManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jason.testapp.android.stella.tracker.MarkerBasedTracker
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import java.util.concurrent.atomic.AtomicBoolean
import org.opencv.imgproc.Imgproc

class DjiMarkerTracker(
    val targetSize: Size,
    ioDispatcher: CoroutineDispatcher,
    val scope: CoroutineScope
) : MarkerBasedTracker<VideoFrame, Unit>(), VideoFrameListener {

    private val dispatcher = ioDispatcher.limitedParallelism(1, "motion tracking dispatcher for marker tracker")

    private val processFrame: Mat = Mat(targetSize.height.toInt(), targetSize.width.toInt(), CvType.CV_8UC1)

    private val isProcessing = AtomicBoolean(false)

    override fun onVideoFrame(frame: VideoFrame) {
        feedFrame(frame)
    }

    override fun feedFrame(frame: VideoFrame) {
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }

        frame.reference()

        val grayBuffer = frame.buffer.slice(0, frame.width * frame.height)

        scope.launch(dispatcher) {
            var tmpMat : Mat? = null
            try {
                tmpMat = Mat(frame.height, frame.width, CvType.CV_8UC1, grayBuffer)
                Imgproc.resize(tmpMat, processFrame, targetSize)
                feedFrame(processFrame)
            } finally {
                isProcessing.set(false)
                tmpMat?.release()
                frame.release()
            }
        }
    }


    override fun startup() {
        super.startup()

        VideoManager.instance.subscribe(this)
    }

    override fun shutdown() {
        VideoManager.instance.unsubscribe(this)

        super.shutdown()
    }

    override fun destroy() {
        isProcessing.set(true)
        scope.launch(dispatcher) {
            super.destroy()
            processFrame.release()
        }
    }
}