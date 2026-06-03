package dji.sampleV5.aircraft.virtualcontroller

import dji.sampleV5.aircraft.ALLOWED_OFFSET
import dji.sampleV5.aircraft.ONLY_OBSERVE_POSITION_CHANGE
import dji.sampleV5.aircraft.SENDING_FREQUENCY
import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.models.ControlStatusData
import dji.sampleV5.aircraft.motiontracking.DjiVSLamTracker
import dji.sampleV5.aircraft.motiontracking.RemotePoseTracker
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.utils.format
import dji.sampleV5.aircraft.utils.toJson
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

}

class VirtualDroneController(
    scope: CoroutineScope,
    controlStatusFeedback: ControlStatusFeedback?,
    private var positionMonitor: IPositionMonitor,
    private val drone: IDrone,
    messageNotifier: MessageNotifier?,
) : BaseDroneController(scope, controlStatusFeedback, messageNotifier) {

    val TAG = "VirtualDroneController"

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
            // clean up old synchronization task
            stopSynchronizationJobs()

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
            drone.reset()
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
        // INFO if the positionMonitor is an instance of RemotePoseTracker,
        //  use a single method get the translation and rotation of the drone to avoid
        //  redundant computation

        // INFO the pose was generated by combining position obtained from remote tracking system,
        //  and rotation obtained from the drone compass.
        //  Therefore, calculating the velocities in drone's different directions (left/right,
        //  forward/backward, upward/downward) involves pose conversion between different coordinate systems.
        val pose = (positionMonitor as? RemotePoseTracker)?.getPose()
            // INFO: the pose was generated by the integrated camera on the drone,
            //  while all position and rotation are based on a single coordinate system,
            //  converting pose between different coordinate system is not necessary
            ?: arrayOf(positionMonitor.getPosition(), positionMonitor.getRotation())

        val dronePos: Vector3D = pose[0]
        val droneAttitude: Vector3D = pose[1]
        val droneAttitudeInDegrees = droneAttitude.y.toDouble()
        val droneAttitudeInRadians = Math.toRadians(droneAttitudeInDegrees)

        Timber.tag(TAG).i("From position $dronePos to target $targetPosition")
        Timber.tag(TAG).i("From attitude: ${droneAttitudeInDegrees.format()} to target: ${targetRotation.y.format()}")

        // INFO while using the position computed by remote tracking system, the coordinate system is same as the Unity one.
        // only care about the x and z axes first
        var xGap = targetPosition.x - dronePos.x
        var zGap = targetPosition.z - dronePos.z

        // INFO in both Unity and the coordinate system of drone, upward represents y-axis
        var yGap = targetPosition.y - dronePos.y

        // INFO currently, there are some delays in the computed position. In a simple process of
        //  guiding the drone to a desired position basing on this position, the drone will
        //  fluctuate around the target position and eventually become still.
        //  -> | ----->
        //  <---- | <-
        //  -> | --->
        //  <-- | <-
        //  -> | ->
        //  | <-

        // INFO if one wants to calculate and accumulate the offset and apply it to the "old"
        //  position to get a temporary "real-time" position before getting the latest position,
        //  Besides the translation and rotation, the tracker must exposes the timestamp of the
        //  position, so that one can test if a position is "old" or "latest" position.
        //  currently, `RemotePoseTracker` hasn't been implemented this functionality yet, but it
        //  is easy to do that. The tough part is how to temporarily compute the position offset
        //  based on the "unreliable", "changing", and instant velocities.

        // offset in 0.10 meter is acceptable
        xGap = if (abs(xGap) > ALLOWED_OFFSET) xGap else 0f
        zGap = if (abs(zGap) > ALLOWED_OFFSET) zGap else 0f
        yGap = if (abs(yGap) > ALLOWED_OFFSET) yGap else 0f

        val zVelocity = zGap / intervalInMillis * 1000.0
        val xVelocity = xGap / intervalInMillis * 1000.0
        val yVelocity = yGap / intervalInMillis * 1000.0

        // TODO ???? really?
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

        Timber.tag(TAG).i("Calculated velocities: F/B-> ${headVelocity.format()}\tR/L-> ${rightVelocity.format()}\tU/D-> ${yVelocity.format()}")
        drone.adjustDroneVelocityOneTime(
            headVelocity,
            rightVelocity,
            yVelocity,
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
        synchronizationJob = null
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
        drone.landOff()
    }

    override suspend fun destroy() {
        if (drone.updateObstacleAvoidance(true)) {
            drone.updateObstacleAvoidanceWarningDistance(4.0)
        }
        drone.setGimbalModel(false)
        drone.setAutomaticControl(false)
    }

}