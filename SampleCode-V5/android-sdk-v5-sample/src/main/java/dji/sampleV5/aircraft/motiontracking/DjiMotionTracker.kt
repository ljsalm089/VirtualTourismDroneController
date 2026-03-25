package dji.sampleV5.aircraft.motiontracking

import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.media.VideoFrame
import dji.sampleV5.aircraft.media.VideoFrameListener
import dji.sampleV5.aircraft.media.VideoManager
import dji.sampleV5.aircraft.virtualcontroller.IPositionMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jason.testapp.android.stella.tracker.TrackingState
import org.jason.testapp.android.stella.tracker.VSLamTracker
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class DjiMotionTracker(
    val targetSize: Size,
    ioDispatcher: CoroutineDispatcher,
    val scope: CoroutineScope
) :
    VSLamTracker<VideoFrame, Unit>(), VideoFrameListener, IPositionMonitor {

    private val dispatcher = ioDispatcher.limitedParallelism(1, "motion tracking dispatcher")

    private var processFrame: Mat =
        Mat(targetSize.height.toInt(), targetSize.width.toInt(), CvType.CV_8UC1)

    override fun initialize(
        configFilePath: String,
        vocabularyFilePath: String
    ): Boolean {
        return super.initialize(configFilePath, vocabularyFilePath)
    }

    override fun startup() {
        super.startup()

        VideoManager.instance.subscribe(this)
    }

    override fun shutdown() {
        super.shutdown()

        VideoManager.instance.unsubscribe(this)
    }

    override fun feedFrame(frame: VideoFrame) {
        frame.reference()

        scope.launch(dispatcher) {
            val grayBuffer = frame.buffer.slice(0, frame.width * frame.height)
            val tmpMat = Mat(frame.height, frame.width, CvType.CV_8UC1, grayBuffer)

            Imgproc.resize(tmpMat, processFrame, targetSize)

            processFrame(processFrame)

            frame.release()
        }
    }

    override fun onVideoFrame(frame: VideoFrame) {
        feedFrame(frame)
    }

    override fun getPosition(): Vector3D {
        return Vector3D(getCurrentPosition())
    }

    override fun getRotation(): Vector3D {
        return Vector3D(getCurrentRotation())
    }

    override fun start() {
        this.relocalizeCameraPose(DoubleArray(6))
    }

    override fun stop() {
    }

    override fun isMonitoring(): Boolean {
        return this.getTrackingState() == TrackingState.Tracking
    }

}