package dji.sampleV5.aircraft.virtualcontroller

import android.util.Log
import dji.sampleV5.aircraft.ONLY_OBSERVE_POSITION_CHANGE
import dji.sampleV5.aircraft.SENDING_FREQUENCY
import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.models.ControlStatusData
import dji.sampleV5.aircraft.motiontracking.DjiMotionTracker
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.utils.format
import dji.sampleV5.aircraft.utils.toJson
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
    private var observable: RawDataObservable,
    messageNotifier: MessageNotifier?,
) : BaseDroneController(scope, controlStatusFeedback, messageNotifier) {

    private val expectedTakeOffHeight = 1.2f

    private var droneParam: VirtualStickFlightControlParam

    private var sendingCmdJob: Job? = null

    private var synchronizationJob: Job? = null

    private var targetPosition: Vector3D = Vector3D(0f, 0f, 0f)

    private var targetRotation: Vector3D = Vector3D(0f, 0f, 0f)

    init {
        scope.launch(Dispatchers.IO) {
            setObstacleAvoidanceWarningDistance(0.1)
            setObstacleAvoidance(false)

            setHeightLimit(2)
        }

        droneParam = initDroneAdvancedParam()
        // TODO just for test
        droneParam.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
    }

    override suspend fun switchDroneStatus(isReady: Boolean) {
        if (isReady) {
            if (ONLY_OBSERVE_POSITION_CHANGE || changeVirtualStickStatus(true)) {
                if (!ONLY_OBSERVE_POSITION_CHANGE) {
                    VirtualStickManager.getInstance()
                        .setVirtualStickAdvancedModeEnabled(true)
                }
                Timber.d("reset the position of the vslam")
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
            adjustDroneVelocityOneTimeBodyBased(0.0, 0.0, 0.0, null)
            // disable advanced virtual stick control
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
            // disable virtual stick control
            changeVirtualStickStatus(false)
            positionMonitor.stop()
        }
    }

    private fun launchSynchronizationJob(): Job {
        val intervalInMillis = (1000 / SENDING_FREQUENCY).toLong()
        return scope.launch(Dispatchers.IO) {
            while (this.isActive) {
                if (ONLY_OBSERVE_POSITION_CHANGE) delay(intervalInMillis)
                if (positionMonitor.isMonitoring()) {
                    synchronizeDronePosture(intervalInMillis)
                    delay(intervalInMillis)
                } else {
                    // currently drone position tracking is invalid, stop the drone control for its safety.
                    adjustDroneVelocityOneTimeBodyBased(0.0, 0.0, 0.0, null)
                    delay(intervalInMillis / 10)
                }
            }
        }
    }

    private fun synchronizeDronePosture(intervalInMillis: Long) {
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
        var yGap = targetPosition.y - dronePos.y

        // offset in 0.05 is acceptable
        xGap = if (abs(xGap) > 0.05) xGap else 0f
        zGap = if (abs(zGap) > 0.05) zGap else 0f
        yGap = if (abs(yGap) > 0.05) yGap else 0f

        val zVelocity = zGap / intervalInMillis * 1000.0
        val xVelocity = xGap / intervalInMillis * 1000.0
        val yVelocity = yGap / intervalInMillis * 1000.0

        val headVelocity = xVelocity * sin(droneAttitudeInRadians) + zVelocity * cos(droneAttitudeInRadians)
        val rightVelocity = xVelocity * cos(droneAttitudeInRadians) - zVelocity * sin(droneAttitudeInRadians)

        val targetAttitude = if (shortestAngle(droneAttitudeInDegrees, targetRotation.y.toDouble()) >= 1.0) {
            (positionMonitor as DjiMotionTracker).formatAttitude(targetRotation.y.toDouble())
        } else {
            null
        }

        adjustDroneVelocityOneTimeBodyBased(
            headVelocity,
            rightVelocity,
            - yVelocity,
            targetAttitude
        )

        // calculate camera orientation and change the gimbal
        // x for raising and setting the gimbal
        adjustCameraOrientation(
            targetRotation.x.toDouble(),
            targetRotation.z.toDouble(),
            intervalInMillis / 1000.0
        )
    }

    private fun stopSynchronizationJobs() {
        synchronizationJob?.cancel()
    }

    private suspend fun getDroneReady(): Unit = suspendCancellableCoroutine { continuation ->
        // for now, there is no obvious status regarding this
        // the only way to do is to check if the drone reaches the height obtained from `KeyAircraftAttitude`
        // however, the height recognition is not that accurate.
        Timber.d("Not flying, take off the drone first")

        // not flying, takeoff first
        KeyTools.createKey(FlightControllerKey.KeyStartTakeoff).action()

        val prepareJob = scope.launch(Dispatchers.IO) {
            val ultrasonicHeightKey = FlightControllerKey.KeyUltrasonicHeight
            var initHeight = 0.0

            Timber.d("Register ultrasonic height listener")
            val rawDataObserver = observable.register(ultrasonicHeightKey) { key, value ->
                if (ultrasonicHeightKey.innerIdentifier == key.innerIdentifier && null != (value as? Int)) {
                    Timber.d("Retrieved valid height from ultrasonic (#2): $value")
                    initHeight = value / 10.0
                }
            }

            // detect the height of the drone
            var isAroundExpectedHeight: Boolean
            do {
                delay(100)

                isAroundExpectedHeight =
                    (abs(initHeight - expectedTakeOffHeight) <= abs(expectedTakeOffHeight / 10f))
            } while (this.isActive && !isAroundExpectedHeight)

            Timber.d("The drone is ready or the task becomes invalid (${!this.isActive})")

            observable.unregister(ultrasonicHeightKey, rawDataObserver)

            if (this.isActive) {
                continuation.resume(Unit)
            }
        }

        continuation.invokeOnCancellation {
            prepareJob.cancel()
        }
    }

    override suspend fun onControllerStatusData(data: ControlStatusData) {
        if (isDroneReady()) {
            // update the received position and rotation to the control strategy
            targetPosition = data.currentPosition
            targetRotation = data.currentRotation
        }
    }

    override suspend fun prepareDrone(controlMode: Int) {
        if (!isDroneFlying()) {
            getDroneReady()
        } else {
            Timber.d("already flying, set the status to ready, ignore setting home location and enable virtual stick control")
        }
        switchDroneStatus(true)
    }

    private suspend fun isDroneFlying(): Boolean = suspendCancellableCoroutine { continuation ->
        KeyTools.createKey(FlightControllerKey.KeyIsFlying).get({ flying ->
            flying?.let {
                continuation.resume(it) { cause, _, _ -> null?.let { it1 -> it1(cause) } }
            } ?: continuation.resumeWithException(Exception("Unable to check if drone is flying"))
            continuation
        }, {
            continuation.resumeWithException(Exception("Unable to check if drone is flying(${it.errorCode()}): ${it.hint()}"))
        })
    }

    private suspend fun resetGimbal(): Boolean = suspendCancellableCoroutine { continuation ->
        val attitude = Attitude(0.0, 0.0, 0.0)
        KeyTools.createKey(GimbalKey.KeyGimbalAttitude).set(
            attitude, {
                Timber.d("Reset the gimbal successfully")
                continuation.resume(true)
            }, {
                Timber.d("Failed to reset the gimbal: ${it.description()}")
                continuation.resume(false)
            }
        )
    }

    override suspend fun abort() {
        switchDroneStatus(false)
    }

    override suspend fun landOff() {
        Timber.d("Start to land off the drone.")
        KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding).action()
    }

    override suspend fun destroy() {
        if (setObstacleAvoidance(true)) {
            setObstacleAvoidanceWarningDistance(4.0)
        }

        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
        changeVirtualStickStatus(false)
    }

    override fun riseAndSetGimbal(angle: Double) {

    }

    private suspend fun setObstacleAvoidanceWarningDistance(distance: Double): Boolean =
        suspendCancellableCoroutine { continuation ->
            // only callback after three settings are successful, or one of them is fail
            val callback = object : CommonCallbacks.CompletionCallback {
                val callbackCount = AtomicInteger(0)

                override fun onSuccess() {
                    if (callbackCount.incrementAndGet() == 3
                        && !continuation.isCompleted
                        && continuation.isActive
                        && !continuation.isCancelled
                    ) {
                        continuation.resume(true)
                    }
                }

                override fun onFailure(p0: IDJIError) {
                    if (!continuation.isCompleted && continuation.isActive && !continuation.isCancelled) {
                        continuation.resume(false)
                    }
                }

            }

            listOf(
                PerceptionDirection.HORIZONTAL,
                PerceptionDirection.DOWNWARD,
                PerceptionDirection.UPWARD
            ).forEach { direction ->
                PerceptionManager.getInstance().setObstacleAvoidanceWarningDistance(
                    distance,
                    direction,
                    callback
                )
            }
        }

    private suspend fun setObstacleAvoidance(enable: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            // right here, can use the `setObstacleAvoidanceEnabled` to enable/disable obstacle avoidance in three directions,
            // because the mini 3 pro does not support this kind of operation
            PerceptionManager.getInstance().setObstacleAvoidanceType(
                if (enable) ObstacleAvoidanceType.BYPASS else ObstacleAvoidanceType.CLOSE,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        messageNotifier?.invoke(
                            Log.DEBUG,
                            "${if (enable) "Enable" else "Disable"} obstacle avoidance successfully",
                            null
                        )
                        continuation.resume(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier?.invoke(
                            Log.ERROR,
                            "${if (enable) "Enable" else "Disable"} obstacle avoidance failed (${p0.errorCode()}): ${p0.hint()}",
                            null
                        )
                        continuation.resume(false)
                    }
                })
        }

    private suspend fun setHeightLimit(limit: Int): Boolean =
        suspendCancellableCoroutine { continuation ->
            // set maximum height the drone can fly
            KeyTools.createKey(FlightControllerKey.KeyHeightLimit).set(limit, {
                messageNotifier?.invoke(
                    Log.DEBUG,
                    "Set the maximum height of drone successfully",
                    null
                )
                continuation.resume(true)
            }, {
                messageNotifier?.invoke(
                    Log.ERROR,
                    "Failed to set maximum height of drone (${it.errorCode()}): ${it.hint()}",
                    null
                )
                continuation.resume(false)
            })
        }

    private suspend fun changeVirtualStickStatus(enable: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    continuation.resume(true)
                }

                override fun onFailure(p0: IDJIError) {
                    continuation.resume(false)
                }
            }
            if (enable) {
                VirtualStickManager.getInstance().enableVirtualStick(callback)
            } else {
                VirtualStickManager.getInstance().disableVirtualStick(callback)
            }
        }
}