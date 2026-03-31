package dji.sampleV5.aircraft.motiontracking

import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.media.VideoFrame
import dji.sampleV5.aircraft.media.VideoFrameListener
import dji.sampleV5.aircraft.media.VideoManager
import dji.sampleV5.aircraft.virtualcontroller.IPositionMonitor
import dji.sampleV5.aircraft.virtualcontroller.OnRawDataObserver
import dji.sampleV5.aircraft.virtualcontroller.RawDataObservable
import dji.sampleV5.aircraft.virtualcontroller.shortestAngle
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jason.testapp.android.stella.tracker.TrackingState
import org.jason.testapp.android.stella.tracker.VSLamTracker
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import timber.log.Timber

class DjiMotionTracker(
    val targetSize: Size,
    val rawDataObservable: RawDataObservable,
    ioDispatcher: CoroutineDispatcher,
    val scope: CoroutineScope
) :
    VSLamTracker<VideoFrame, Unit>(), VideoFrameListener, IPositionMonitor, OnRawDataObserver {

    private val dispatcher = ioDispatcher.limitedParallelism(1, "motion tracking dispatcher")

    private val gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude

    private val attitudeKey = FlightControllerKey.KeyAircraftAttitude

    private var benchmarkAttitude: Double = 0.0

    private var benchmarkPosition: Vector3D = Vector3D(DoubleArray(3))

    private var currentAttitude: Double = 0.0

    private var gimbalAttitude: DoubleArray = DoubleArray(2)

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
        rawDataObservable.register(gimbalAttitudeKey, this)
        rawDataObservable.register(attitudeKey, this)
    }

    override fun shutdown() {
        super.shutdown()

        VideoManager.instance.unsubscribe(this)
        rawDataObservable.unregister(gimbalAttitudeKey, this)
        rawDataObservable.unregister(attitudeKey, this)
    }

    override fun feedFrame(frame: VideoFrame) {
        frame.reference()

        scope.launch(dispatcher) {
            val grayBuffer = frame.buffer.slice(0, frame.width * frame.height)
            val tmpMat = Mat(frame.height, frame.width, CvType.CV_8UC1, grayBuffer)

            Imgproc.resize(tmpMat, processFrame, targetSize)

            processFrame(processFrame)

            tmpMat.release()
            frame.release()
        }
    }

    override fun invoke(p1: DJIKeyInfo<*>, p2: Any?) {
        if (p1.innerIdentifier == gimbalAttitudeKey.innerIdentifier) {
            (p2 as? Attitude)?.let {
                gimbalAttitude[0] = it.pitch
                gimbalAttitude[1] = it.roll
            }
        } else if (p1.innerIdentifier == attitudeKey.innerIdentifier) {
            (p2 as? Attitude)?.let {
                currentAttitude = it.yaw.toDouble()
            }
        }
    }

    override fun onVideoFrame(frame: VideoFrame) {
        feedFrame(frame)
    }

    override fun getCurrentPosition(): DoubleArray {
        val position = super.getCurrentPosition()
        position[0] -= benchmarkPosition.x
        position[1] -= benchmarkPosition.y
        position[2] -= benchmarkPosition.z
        return position
    }

    override fun getPosition(): Vector3D {
        return Vector3D(getCurrentPosition())
    }

    override fun getCurrentRotation(): DoubleArray {
        val rotation = super.getCurrentRotation()
        rotation[0] = gimbalAttitude[0]
        rotation[1] = shortestAngle(currentAttitude, benchmarkAttitude)
        rotation[2] = gimbalAttitude[1]
        return rotation
    }

    override fun getRotation(): Vector3D {
        // the rotation is obtained from the drone information
        return Vector3D(getCurrentRotation())
    }

    fun formatAttitude(targetAttitude: Double): Double {
        return ((benchmarkAttitude + 180 + targetAttitude) + 360) % 360 - 180
    }

    override fun start() {
        // reset the location and orientation
        // the operation of relocalization is asynchronous
        this.relocalizeCameraPose(DoubleArray(6))
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Timber.e(e)
        }
        benchmarkPosition = Vector3D(super.getCurrentPosition())
        Timber.d("Reset the vslam camera pose, current position: ${getPosition()}")
        this.benchmarkAttitude = currentAttitude
    }

    override fun stop() {
    }

    override fun isMonitoring(): Boolean {
        return this.getTrackingState() == TrackingState.Tracking
    }

    override fun destroy() {
        super.destroy()
        processFrame.release()
    }

}
