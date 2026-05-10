package dji.sampleV5.aircraft.motiontracking

import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.virtualcontroller.IPositionMonitor
import dji.sampleV5.aircraft.virtualcontroller.OnRawDataObserver
import dji.sampleV5.aircraft.virtualcontroller.RawDataObservable
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.common.Attitude
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.jason.testapp.android.stella.tracker.TrackingState

data class Orientation(
    val roll: Float,
    val pitch: Float,
    val yaw: Float
)

data class ObjectPose(
    val position: Vector3D,
    val orientation: Orientation,
    val timestamp: Long
)

class RemotePoseTracker(
    val rawDataObservable: RawDataObservable,
    ioDispatcher: CoroutineDispatcher,
    val scope: CoroutineScope
) : IPositionMonitor, OnRawDataObserver {

    private val gimbalAttitudeKey = GimbalKey.KeyGimbalAttitude

    private val attitudeKey = FlightControllerKey.KeyAircraftAttitude

    private var currentAttitude: Double = 0.0

    private var benchmarkAttitude: Double = 0.0

    private var remoteBenchmarkAttitude: Double = 0.0

    private var currentPosition: Vector3D = Vector3D()

    private var benchmarkPosition: Vector3D = Vector3D()

    private val gimbalAttitude = DoubleArray(2)

    override fun getPosition(): Vector3D {
        TODO("Not yet implemented")
    }

    override fun getRotation(): Vector3D {
        TODO("Not yet implemented")
    }

    override fun start() {
        rawDataObservable.register(gimbalAttitudeKey, this)
        rawDataObservable.register(attitudeKey, this)
    }

    override fun stop() {
        rawDataObservable.unregister(gimbalAttitudeKey, this)
        rawDataObservable.unregister(attitudeKey, this)
    }

    override fun isMonitoring(): Boolean {
        TODO("Not yet implemented")
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

    fun updatePose(pose: ObjectPose) {

    }

    fun getTrackingState(): TrackingState {
        return if (isMonitoring()) TrackingState.Tracking else TrackingState.Lost
    }
}