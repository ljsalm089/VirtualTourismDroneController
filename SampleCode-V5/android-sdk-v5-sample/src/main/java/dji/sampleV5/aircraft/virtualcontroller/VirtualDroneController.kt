package dji.sampleV5.aircraft.virtualcontroller

import dji.sampleV5.aircraft.ALLOWED_OFFSET
import dji.sampleV5.aircraft.ONLY_OBSERVE_POSITION_CHANGE
import dji.sampleV5.aircraft.SENDING_FREQUENCY
import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.models.ControlStatusData
import dji.sampleV5.aircraft.motiontracking.DjiVSLamTracker
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.utils.format
import dji.sampleV5.aircraft.utils.toJson
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.v5.et.action
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin


typealias ControlStatusFeedback = (String, String) -> Unit
typealias MessageNotifier = (Int, String, Throwable?) -> Unit


interface IDroneController {

    suspend fun prepareDrone(controlMode: Int)

    suspend fun abort()

    suspend fun landOff()

    fun riseAndSetGimbal(angle: Double)

    suspend fun onControllerStatusData(data: ControlStatusData)

    suspend fun destroy()

    fun isDroneReady(): Boolean

}

abstract class BaseDroneController(
    protected val scope: CoroutineScope,
    protected val controlStatusFeedback: ControlStatusFeedback?,
    protected val messageNotifier: MessageNotifier?,
) : IDroneController {

    private var isReady: Boolean = false

    override fun isDroneReady(): Boolean {
        return isReady
    }

    protected open suspend fun switchDroneStatus(isReady: Boolean) {
        if (isReady) {
            this@BaseDroneController.isReady = true

            controlStatusFeedback?.invoke("Control", "Start")
        } else {
            controlStatusFeedback?.invoke("Control", "Stop")

            this@BaseDroneController.isReady = false
        }
    }

    override suspend fun landOff() {
    }
}

class MockDroneController(
    scope: CoroutineScope,
    controlStatusFeedback: ControlStatusFeedback?,
    messageNotifier: MessageNotifier?,
) : BaseDroneController(scope, controlStatusFeedback, messageNotifier) {

    private val SESSION_ID = UUID.randomUUID().toString()

    override suspend fun prepareDrone(controlMode: Int) {
        Timber.d("Mock to start the control")
        switchDroneStatus(true)
    }

    override suspend fun abort() {
        Timber.d("Mock to abort the control")
        switchDroneStatus(false)
    }

    override suspend fun onControllerStatusData(data: ControlStatusData) {
        val dataString = data.toJson()
        Timber.d("Received status changes from remote controller: $dataString")

        // TODO log the target position data into file for future analysis
        Timber.d("Log the target position data into file for future analysis")
        Timber.log(LogLevel.VERBOSE_HEADSET_POSITION_CHANGES, "$SESSION_ID ---> $dataString")
    }

    override suspend fun destroy() {
    }

    override fun riseAndSetGimbal(angle: Double) {
    }
}

class VirtualDroneController(
    scope: CoroutineScope,
    controlStatusFeedback: ControlStatusFeedback?,
    private var positionMonitor: IPositionMonitor,
    private val drone: IDrone,
    messageNotifier: MessageNotifier?,
) : BaseDroneController(scope, controlStatusFeedback, messageNotifier) {

    private var synchronizationJob: Job? = null

    private var targetPosition: Vector3D = Vector3D(0f, 0f, 0f)

    private var targetRotation: Vector3D = Vector3D(0f, 0f, 0f)

    init {
        scope.launch(Dispatchers.IO) {
            delay(300)
            drone.updateObstacleAvoidanceWarningDistance(0.1)
            drone.updateObstacleAvoidance(false)
            if (drone.setGimbalModel(true)) {
                drone.adjustCameraOrientation(0.0, 0.0, 40.0)
            }
            drone.setHeightLimit(2)
        }
    }

    override suspend fun switchDroneStatus(isReady: Boolean) {
        if (isReady) {
            if (ONLY_OBSERVE_POSITION_CHANGE || drone.setAutomaticControl(true)) {
                positionMonitor.start()
                super.switchDroneStatus(true)

                // INFO launch periodic task to synchronize drone posture to the headset
                synchronizationJob = launchSynchronizationJob()
            } else {
                positionMonitor.stop()
                throw Exception("Unable to enable virtual stick")
            }
        } else {
            super.switchDroneStatus(false)
            // INFO cancel periodic task
            stopSynchronizationJobs()
            // INFO reset the existing velocity in every direction, reset the gimbal angle to origin
            drone.adjustDroneVelocityOneTime(0.0, 0.0, 0.0, null)
            // disable advanced virtual stick control
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
            // disable virtual stick control
            drone.setAutomaticControl(false)
            positionMonitor.stop()
        }
    }

    private fun launchSynchronizationJob(): Job {
        val intervalInMillis = (1000 / SENDING_FREQUENCY).toLong()
        return scope.launch(Dispatchers.IO) {
            while (this.isActive) {
                if (ONLY_OBSERVE_POSITION_CHANGE) {
                    delay(intervalInMillis)
                    continue
                }

                if (positionMonitor.isMonitoring()) {
                    synchronizeDronePosture(intervalInMillis)
                    delay(intervalInMillis)
                } else {
                    // currently drone position tracking is invalid, stop the drone control for its safety.
                    drone.adjustDroneVelocityOneTime(0.0, 0.0, 0.0, null)
                    delay(intervalInMillis / 10)
                }
            }
        }
    }

    private suspend fun synchronizeDronePosture(intervalInMillis: Long) {
        // TODO neglect the direction first, only care about the position changes
        val dronePos = positionMonitor.getPosition()
        val droneAttitudeInDegrees = positionMonitor.getRotation().y.toDouble()
        val droneAttitudeInRadians = droneAttitudeInDegrees.degreesToRadians()

        Timber.i("From position $dronePos to target $targetPosition")
        Timber.i("From attitude: ${droneAttitudeInDegrees.format()} to target: ${targetRotation.y.format()}")


        // only care about the x and z axes first
        var xGap = targetPosition.x - dronePos.x
        var zGap = targetPosition.z - dronePos.z

        // TODO in Unity, y is the direction of up,
        //  but in stella vslam or opencv, y is the direction of down, which is same as the drone
        var yGap = -targetPosition.y - dronePos.y

        // offset in 0.05 is acceptable
        xGap = if (abs(xGap) > ALLOWED_OFFSET) xGap else 0f
        zGap = if (abs(zGap) > ALLOWED_OFFSET) zGap else 0f
        yGap = if (abs(yGap) > ALLOWED_OFFSET) yGap else 0f

        val zVelocity = zGap / intervalInMillis * 1000.0
        val xVelocity = xGap / intervalInMillis * 1000.0
        val yVelocity = yGap / intervalInMillis * 1000.0

        val headVelocity =
            -xVelocity * sin(droneAttitudeInRadians) + zVelocity * cos(droneAttitudeInRadians)
        val rightVelocity =
            xVelocity * cos(droneAttitudeInRadians) + zVelocity * sin(droneAttitudeInRadians)

        val targetAttitude =
            if (shortestAngle(droneAttitudeInDegrees, targetRotation.y.toDouble()) >= 1.0) {
                (positionMonitor as DjiVSLamTracker).formatAttitude(targetRotation.y.toDouble())
            } else {
                null
            }

        drone.adjustDroneVelocityOneTime(
            headVelocity,
            rightVelocity,
            -yVelocity,
            targetAttitude
        )

        // calculate camera orientation and change the gimbal
        // x for raising and setting the gimbal
        drone.adjustCameraOrientation(
            targetRotation.x.toDouble(),
            targetRotation.z.toDouble(),
            intervalInMillis / 1000.0
        )
    }

    private fun stopSynchronizationJobs() {
        synchronizationJob?.cancel()
    }

    override suspend fun onControllerStatusData(data: ControlStatusData) {
        if (isDroneReady()) {
            // update the received position and rotation to the control strategy
            targetPosition = data.currentPosition
            targetRotation = data.currentRotation
        }
    }

    override suspend fun prepareDrone(controlMode: Int) {
        if (drone.getReady()) switchDroneStatus(true)
    }


    override suspend fun abort() {
        switchDroneStatus(false)
    }

    override suspend fun landOff() {
        Timber.d("Start to land off the drone.")
        KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding).action()
    }

    override suspend fun destroy() {
        if (drone.updateObstacleAvoidance(true)) {
            drone.updateObstacleAvoidanceWarningDistance(4.0)
        }
        drone.setGimbalModel(false)
        drone.setAutomaticControl(false)
    }

    override fun riseAndSetGimbal(angle: Double) {

    }

}