package dji.sampleV5.aircraft.motiontracking

import dji.sampleV5.aircraft.VSLAM_POSITION_SCALE
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.jason.testapp.android.stella.tracker.TrackingState
import org.jason.testapp.android.stella.tracker.VSlamTracker
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import timber.log.Timber

class DjiVSLamTracker(
    val targetSize: Size,
    val rawDataObservable: RawDataObservable,
    ioDispatcher: CoroutineDispatcher,
    val scope: CoroutineScope
) :
    VSlamTracker<VideoFrame, Unit>(), VideoFrameListener, IPositionMonitor, OnRawDataObserver {

    private val dispatcher = ioDispatcher.limitedParallelism(1, "motion tracking dispatcher")

    private val gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude

    private val attitudeKey = FlightControllerKey.KeyAircraftAttitude

    @Volatile
    private var benchmarkAttitude: Double = 0.0

    @Volatile
    private var benchmarkPosition: Vector3D = Vector3D(DoubleArray(3))

    private var currentAttitude: Double = 0.0

    private var gimbalAttitude: DoubleArray = DoubleArray(2)

    private var processFrame: Mat =
        Mat(targetSize.height.toInt(), targetSize.width.toInt(), CvType.CV_8UC1)

    private val isProcessing = AtomicBoolean(false)

    // Incremented after every completed feed_monocular_frame() call.
    // Used by start() to detect when the SLAM has processed its first frame
    // after a relocalization request — the only reliable signal that the new
    // pose origin has actually been applied.
    private val processedFrameCount = AtomicLong(0)

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
        VideoManager.instance.unsubscribe(this)
        rawDataObservable.unregister(gimbalAttitudeKey, this)
        rawDataObservable.unregister(attitudeKey, this)

        scope.launch(dispatcher) {
            super.shutdown()
        }
    }

    override fun feedFrame(frame: VideoFrame) {
        if (!isProcessing.compareAndSet(false, true)) {
            return
        }
        
        frame.reference()

        scope.launch(dispatcher) {
            try {
                val grayBuffer = frame.buffer.slice(0, frame.width * frame.height)
                val tmpMat = Mat(frame.height, frame.width, CvType.CV_8UC1, grayBuffer)

                Imgproc.resize(tmpMat, processFrame, targetSize)

                processFrame(processFrame, frame.frameTimeStampInSeconds)
                processedFrameCount.incrementAndGet()

                tmpMat.release()
            } finally {
                isProcessing.set(false)
                frame.release()
            }
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
        return position.map { it * VSLAM_POSITION_SCALE }.toDoubleArray()
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
        // relocalizeCameraPose() only sets a flag inside stella_vslam's tracking
        // module — it does NOT update the pose immediately. The new origin is
        // applied the next time feed_monocular_frame() runs (i.e., the next
        // dispatched feedFrame job). Polling getTrackingState() is therefore
        // useless as a synchronisation signal here.
        //
        // Instead we snapshot processedFrameCount before the request and wait
        // until 2 more frames have completed:
        //   • Frame N  — may already be mid-flight when we call relocalize, so
        //                the flag might be read only at the very end of that call
        //                or not at all in that cycle.
        //   • Frame N+1 — guaranteed to start after the flag is set, so the new
        //                 pose is definitely applied by the time this frame ends.
        //   • We wait for N+2 to be safe and ensure getCurrentPosition() already
        //     reflects the relocated origin when we snapshot it.
        this.relocalizeCameraPose(DoubleArray(6))

        val framesBefore = processedFrameCount.get()
        val requiredFrames = framesBefore + 2
        val timeoutMs  = 3_000L
        val pollStepMs =    20L
        var elapsedMs  =     0L

        while (processedFrameCount.get() < requiredFrames && elapsedMs < timeoutMs) {
            try {
                Thread.sleep(pollStepMs)
            } catch (e: InterruptedException) {
                Timber.w(e, "Interrupted while waiting for post-relocalization frames")
                Thread.currentThread().interrupt()
                break
            }
            elapsedMs += pollStepMs
        }

        val framesProcessed = processedFrameCount.get() - framesBefore
        if (framesProcessed < 2) {
            Timber.w("Only $framesProcessed frame(s) processed within ${timeoutMs}ms after relocalization — benchmark may be stale")
        } else {
            Timber.d("Relocalization confirmed after $framesProcessed frames (${elapsedMs}ms)")
        }

        benchmarkPosition = Vector3D(super.getCurrentPosition())
        benchmarkAttitude = currentAttitude
        Timber.d("Reset the vslam camera pose, current position: ${getPosition()}")
    }

    override fun stop() {
    }

    override fun isMonitoring(): Boolean {
        return this.getTrackingState() == TrackingState.Tracking
    }

    override fun destroy() {
        isProcessing.set(true)
        scope.launch(dispatcher) {
            super.destroy()
            processFrame.release()
        }
    }

}
