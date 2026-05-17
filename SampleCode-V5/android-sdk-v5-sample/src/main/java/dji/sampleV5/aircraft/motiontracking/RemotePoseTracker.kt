package dji.sampleV5.aircraft.motiontracking

import android.os.SystemClock
import dji.sampleV5.aircraft.data.Vector3D
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jason.testapp.android.stella.tracker.IMotionTracker
import org.jason.testapp.android.stella.tracker.TrackingState
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat

data class Orientation(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val yaw: Float = 0f
)

data class ObjectPose(
    val position: Vector3D,
    val orientation: Orientation,
    val timestamp: Long,
    val localTimestamp: Long = SystemClock.elapsedRealtime()
)

class RemotePoseTracker(
    val rawDataObservable: RawDataObservable,
    ioDispatcher: CoroutineDispatcher,
    val scope: CoroutineScope
) : IPositionMonitor, OnRawDataObserver, IMotionTracker<ObjectPose, Unit> {

    private val gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude

    private val attitudeKey = FlightControllerKey.KeyAircraftAttitude

    private var currentCompassAngle: Double = Double.NaN

    private var benchmarkCompassAngle: Double = Double.NaN

    // TODO the roll angle of the virtual marker on the drone with respect to the benchmark marker
    // INFO do not trust the rotations from the remote tracking system, they are not reliable.
    private var angleBetweenMarkers: Double = 0.0

    private var benchmarkPosition: Vector3D = Vector3D(Float.NaN, Float.NaN, Float.NaN)

    private val gimbalAttitude = DoubleArray(2)

    private var lastPose: ObjectPose? = ObjectPose(Vector3D(), Orientation(), SystemClock.elapsedRealtime())

    private var relativePose: Mat = Mat.eye(4, 4, CvType.CV_64F)

    private var state = TrackingState.Initializing

    private val ioDispatcher = ioDispatcher.limitedParallelism(1)

    private var job: Job? = null

    // only for cache
    private val tmpRVec: Mat = Mat(3, 1, CvType.CV_64F)
    private val tmpTVec = Mat(3, 1, CvType.CV_64F)
    private val tmpR = Mat(3, 3, CvType.CV_64F)
    private val tmpPose = Mat.eye(4, 4, CvType.CV_64F)

    override fun getPosition(): Vector3D {
        // obtain current virtual marker pose, ignore its orientation
        tmpTVec.put(0, 0, *lastPose!!.position.toDoubleArray())
        tmpRVec.put(0, 0, *doubleArrayOf(0.0, 0.0, 0.0))

        Calib3d.Rodrigues(tmpRVec, tmpR)
        tmpR.copyTo(tmpPose.submat(0, 3, 0, 3))
        tmpTVec.copyTo(tmpPose.submat(0, 3, 3, 4))

        // convert the pose in marker based coordinate system into the one based on drone initial pose
        val newPose = tmpPose.matMul(relativePose)

        // extract the translation from the new pose
        return Vector3D(
            newPose.get(0, 3)[0].toFloat(),
            newPose.get(1, 3)[0].toFloat(),
            newPose.get(2, 3)[0].toFloat()
        )
    }

    override fun getRotation(): Vector3D {
        return Vector3D(gimbalAttitude[0].toFloat(), gimbalAttitude[1].toFloat(), shortestAngle(benchmarkCompassAngle, currentCompassAngle).toFloat())
    }

    override fun start() {
        state = TrackingState.Initializing

        benchmarkCompassAngle = Double.NaN
        benchmarkPosition.invalid()

        job?.cancel()

        job = scope.launch(ioDispatcher) {
            // exit until get a valid remote orientation, remote position, and drone orientation
            while (benchmarkPosition.isInvalid() || benchmarkCompassAngle.isNaN()) {
                if (lastPose?.isFresh() == true && !currentCompassAngle.isNaN()) {
                    benchmarkCompassAngle = currentCompassAngle
                    benchmarkPosition = lastPose!!.position

                    val tvec = Mat(3, 1, CvType.CV_64F)
                    tvec.put(0, 0, *lastPose!!.position.toDoubleArray())

                    val rvec = Mat(3, 1, CvType.CV_64F);
                    rvec.put(
                        0,
                        0,
                        *doubleArrayOf(0.0, 0.0, Math.toRadians(angleBetweenMarkers))
                    )

                    val r = Mat(3, 3, CvType.CV_64F)
                    val currentPose = Mat.eye(4, 4, CvType.CV_64F)

                    Calib3d.Rodrigues(rvec, r)
                    r.copyTo(currentPose.submat(0, 3, 0, 3))
                    tvec.copyTo(currentPose.submat(0, 3, 3, 4))

                    relativePose = currentPose.inv()
                }
                delay(10)
            }
            state = TrackingState.Tracking
        }
    }

    override fun stop() {
        job?.cancel()

        state = TrackingState.Initializing
    }

    override fun isMonitoring() = getTrackingState() == TrackingState.Tracking

    override fun invoke(p1: DJIKeyInfo<*>, p2: Any?) {
        if (p1.innerIdentifier == gimbalAttitudeKey.innerIdentifier) {
            (p2 as? Attitude)?.let {
                gimbalAttitude[0] = it.pitch
                gimbalAttitude[1] = it.roll
            }
        } else if (p1.innerIdentifier == attitudeKey.innerIdentifier) {
            (p2 as? Attitude)?.let {
                currentCompassAngle = it.yaw.toDouble()
            }
        }
    }

    override fun feedFrame(pose: ObjectPose) {
        if (null == lastPose || pose.timestamp > lastPose!!.timestamp) {
            lastPose = pose
        }
    }

    override fun getTrackingState(): TrackingState {
        if (state != TrackingState.Tracking) return state
        // if no message received in the last 100 ms, indicate current state as lose tracking
        state = if (lastPose?.isFresh() == true) TrackingState.Tracking else TrackingState.Lost
        return state
    }

    override fun getCurrentPosition(): DoubleArray {
        val result = getPosition()
        return doubleArrayOf(result.x.toDouble(), result.y.toDouble(), result.z.toDouble())
    }

    override fun getCurrentRotation(): DoubleArray {
        val result = getRotation()
        return doubleArrayOf(result.x.toDouble(), result.y.toDouble(), result.z.toDouble())
    }

    override fun startup() {
        rawDataObservable.register(gimbalAttitudeKey, this)
        rawDataObservable.register(attitudeKey, this)
    }

    override fun shutdown() {
        rawDataObservable.unregister(gimbalAttitudeKey, this)
        rawDataObservable.unregister(attitudeKey, this)
    }

    override fun destroy() {
    }

    private fun Vector3D.isInvalid(): Boolean = x.isNaN() || y.isNaN() || z.isNaN()

    private fun Vector3D.invalid() {
        x = Float.NaN
        y = Float.NaN
        z = Float.NaN
    }

    private fun ObjectPose.isFresh(): Boolean =
        SystemClock.elapsedRealtime() - localTimestamp <= 100L
}