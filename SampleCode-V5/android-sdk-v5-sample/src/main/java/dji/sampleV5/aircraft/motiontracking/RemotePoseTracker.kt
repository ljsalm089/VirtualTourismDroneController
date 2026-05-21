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
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import timber.log.Timber

data class Orientation(
    val roll: Float = 0f,
    val pitch: Float = 0f,
    val yaw: Float = 0f
)

data class ObjectPose(
    val position: Vector3D,
    val orientation: Orientation,
    val timestamp: Long,
    var localTimestamp: Long
)

class RemotePoseTracker(
    val rawDataObservable: RawDataObservable,
    ioDispatcher: CoroutineDispatcher,
    val scope: CoroutineScope
) : IPositionMonitor, OnRawDataObserver, IMotionTracker<ObjectPose, Unit> {

    private val TAG = RemotePoseTracker::class.java.simpleName

    private val gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude

    private val attitudeKey = FlightControllerKey.KeyAircraftAttitude

    private var currentCompassAngle: Double = Double.NaN

    // INFO once get the position of the drone from the tracking system and the orientation from the drone compass,
    // INFO mark the orientation of the benchmark marker in the compass
    private var orientationOfBenchmarkMarkerInCompass: Double = Double.NaN

    // INFO the angle between drone's head and the y axis of the benchmark marker, range from -180 (left) to 180 (right)
    private val initialAngleInDegreeBetweenDroneAndBenchmarkMarker: Double = -90.0

    private val gimbalAttitude = DoubleArray(2)

    private var lastPose: ObjectPose? = null

    // INFO a pose used to convert the position from tracking system into the pose related to the start snapshot
    private var relativePose: Mat = Mat.eye(4, 4, CvType.CV_64F)

    private var state = TrackingState.Initializing

    private val ioDispatcher = ioDispatcher.limitedParallelism(1)

    private var job: Job? = null

    // only for cache
    private val tmpRVec: Mat = Mat(3, 1, CvType.CV_64F)
    private val tmpTVec = Mat(3, 1, CvType.CV_64F)
    private val tmpR = Mat(3, 3, CvType.CV_64F)

    private val tmpPose = Mat.eye(4, 4, CvType.CV_64F)
    private val newTmpPose = Mat.eye(4, 4, CvType.CV_64F)

    override fun getPosition(): Vector3D {
        return getPose()[0]
    }

    fun getPose(): Array<Vector3D> {
        return lastPose?.let {
            // TODO the logic here still needs to be clarified
            // obtain current virtual marker pose, ignore its orientation
            tmpTVec.put(0, 0, *it.position.toDoubleArray())
            tmpRVec.put(
                0,
                0,
                *doubleArrayOf(
                    0.0,
                    0.0,
                    Math.toRadians(
                        shortestAngle(
                            orientationOfBenchmarkMarkerInCompass,
                            currentCompassAngle
                        )
                    )
                )
            )

            Calib3d.Rodrigues(tmpRVec, tmpR)
            tmpR.copyTo(tmpPose.submat(0, 3, 0, 3))
            tmpTVec.copyTo(tmpPose.submat(0, 3, 3, 4))
            Timber.tag(TAG).d("the values of tmpPose:\n${tmpPose.dump()}")

            // convert the pose in marker based coordinate system into the one based on drone initial pose
            // FIXME the computation right here is not correct, need to be fixed
//            val newPose = tmpPose.matMul(relativePose)
            Core.gemm(tmpPose, relativePose, 1.0, Mat(), 0.0, newTmpPose)

            Calib3d.Rodrigues(newTmpPose.submat(0, 3, 0, 3), tmpRVec)

            // extract the translation from the new pose
            arrayOf(
                Vector3D(
                    newTmpPose.get(0, 3)[0].toFloat(),
                    newTmpPose.get(1, 3)[0].toFloat(),
                    newTmpPose.get(2, 3)[0].toFloat()
                ),
                Vector3D(
                    gimbalAttitude[0].toFloat(),
                    gimbalAttitude[1].toFloat(),
                    Math.toDegrees(tmpRVec.get(2, 0)[0]).toFloat()
                )
            )
        } ?: arrayOf(Vector3D(), Vector3D())
    }

    override fun getRotation(): Vector3D {
        return getPose()[1]
    }

    override fun start() {
        state = TrackingState.Initializing

        orientationOfBenchmarkMarkerInCompass = Double.NaN

        job?.cancel()

        job = scope.launch(ioDispatcher) {
            // exit until get a valid remote orientation, remote position, and drone orientation
            while (orientationOfBenchmarkMarkerInCompass.isNaN()) {
                // INFO haven't built the mapping between benchmark marker and drone compass
                delay(30)
            }

            // now the orientationOfBenchmarkMarkerInCompass is not NaN, based on this to build a relative pose

            // INFO the relative pose is the inversion of the current pose.
            // INFO to calculate current pose, get the position from the lastPose, and get the relative rotation of the drone to the benchmark marker
            val translation = lastPose!!.position.toDoubleArray()
            val rotation = DoubleArray(3)
            rotation[2] = Math.toRadians(
                shortestAngle(
                    orientationOfBenchmarkMarkerInCompass,
                    currentCompassAngle
                )
            )

            // compute the relative pose
            val pose = Mat.eye(4, 4, CvType.CV_64F)
            val tmpVector = Mat(3, 1, CvType.CV_64F)

            // put the position into it
            tmpVector.put(0, 0, *translation)
            tmpVector.copyTo(pose.submat(0, 3, 3, 4))

            // convert the orientation from Euler degree into Axis Angle Rotation
            tmpVector.put(0, 0, *rotation)
            val tmpR = Mat(3, 3, CvType.CV_64F)
            Calib3d.Rodrigues(tmpVector, tmpR)
            tmpR.copyTo(pose.submat(0, 3, 0, 3))

            Timber.tag(TAG).d("Before assigning value to the relative pose matrix:\n${relativePose.dump()}")
            Timber.tag(TAG).d("Before assigning value to the relative pose matrix, pose is:\n${pose.dump()}")
            relativePose.release()
            relativePose = pose.inv()
            Timber.tag(TAG).d("After assigning value to the relative pose matrix:\n${relativePose.dump()}")

            pose.release()
            tmpVector.release()
            tmpR.release()
        }
    }

    private fun synchronizeDronePostureAndTrackingSystem() {
        // at the beginning, detect the benchmark marker's orientation in the compass coordinate system
        if (orientationOfBenchmarkMarkerInCompass.isNaN() && null != lastPose && !currentCompassAngle.isNaN()) {
            orientationOfBenchmarkMarkerInCompass = shortestAngle(
                initialAngleInDegreeBetweenDroneAndBenchmarkMarker,
                currentCompassAngle
            )

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

                synchronizeDronePostureAndTrackingSystem()
            }
        }
    }

    override fun feedFrame(pose: ObjectPose) {
        if (null == lastPose || pose.timestamp > lastPose!!.timestamp) {
            lastPose = pose
        }

        synchronizeDronePostureAndTrackingSystem()
    }

    override fun getTrackingState(): TrackingState {
        if (state == TrackingState.Initializing) return state
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
        // once connect to the drone, try to read the compass and gimbal values from the drone
        rawDataObservable.register(gimbalAttitudeKey, this)
        rawDataObservable.register(attitudeKey, this)
    }

    override fun shutdown() {
        rawDataObservable.unregister(gimbalAttitudeKey, this)
        rawDataObservable.unregister(attitudeKey, this)
    }

    override fun destroy() {
        relativePose.release()
        tmpRVec.release()
        tmpTVec.release()
        tmpR.release()
        tmpPose.release()
        newTmpPose.release()
    }

    private fun ObjectPose.isFresh(): Boolean {
        return SystemClock.elapsedRealtime() - localTimestamp <= 500L
    }
}