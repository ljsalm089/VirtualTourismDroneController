package dji.sampleV5.aircraft.virtualcontroller

import android.util.Log
import dji.sampleV5.aircraft.SENDING_FREQUENCY
import dji.sampleV5.aircraft.data.Vector3D
import dji.sampleV5.aircraft.models.ControlStatusData
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.utils.toJson
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs


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
            if (changeVirtualStickStatus(true)) {
                VirtualStickManager.getInstance()
                    .setVirtualStickAdvancedModeEnabled(true)
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
            adjustDroneVelocityOneTimeBodyBased(0.0, 0.0, null, null)
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
                if (positionMonitor.isMonitoring()) {
                    synchronizeDronePosture(intervalInMillis)
                    delay(intervalInMillis)
                } else {
                    // currently drone position tracking is invalid, stop the drone control for its safety.
                    adjustDroneVelocityOneTimeBodyBased(0.0, 0.0, null, null)
                    delay(intervalInMillis / 10)
                }
            }
        }
    }

    private fun synchronizeDronePosture(intervalInMillis: Long) {
        // TODO neglect the direction first, only care about the position changes
        val dronePos = positionMonitor.getPosition()

        // only care about the x and z axes first
        var xGap = targetPosition.x - dronePos.x
        var zGap = targetPosition.z - dronePos.z
        xGap = if (abs(xGap) > 0.10) xGap else 0f
        zGap = if (abs(zGap) > 0.10) zGap else 0f

        adjustDroneVelocityOneTimeBodyBased(
            xGap / intervalInMillis * 1000.0,
            zGap / intervalInMillis * 1000.0,
            null,
            null
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

    /**
     * This method is just for test.
     * In velocity mode and body coordinate system, roll means forward/backward, pitch means right/left
     * yaw: positive rotate towards right, negative rotate towards left
     *
     * TODO In velocity mode and ground coordinate system, roll means x axis (North), pitch means y axis (East),
     *  throttle means z axis (Down), and yaw for rotation
     */
    private fun adjustDroneVelocity(
        roll: Double = 0.0,
        pitch: Double = 0.0,
        yaw: Double = 0.0,
        throttle: Double = 0.0,
    ) {
        droneParam.yaw = yaw
        droneParam.roll = roll
        droneParam.pitch = pitch
        droneParam.verticalThrottle = throttle

        val log =
            "Change drone's velocity: ${if (pitch > 0) "Backward" else "Forward"}: ${abs(pitch)}\t" +
                    "${if (roll >= 0) "Right" else "Left"}: ${abs(roll)}\tRotation: $yaw"
        messageNotifier?.invoke(Log.INFO, log, null)

        if (null == sendingCmdJob || !sendingCmdJob!!.isActive) {
            sendingCmdJob = scope.launch(Dispatchers.IO) {
                while (sendingCmdJob?.isActive == true && isDroneReady()) {
                    // don't output the log to screen, there is too much log
                    Timber.d("Sending advanced stick param to the drone: ${droneParam.toJson()}")
                    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(droneParam)
                    delay(1000L / SENDING_FREQUENCY)
                }
            }
        }
    }

    override fun riseAndSetGimbal(angle: Double) {

    }

    private suspend fun setObstacleAvoidanceWarningDistance(distance: Double): Boolean =
        suspendCancellableCoroutine { continuation ->
            listOf(
                PerceptionDirection.HORIZONTAL,
                PerceptionDirection.DOWNWARD,
                PerceptionDirection.UPWARD
            ).forEach { direction ->
                PerceptionManager.getInstance().setObstacleAvoidanceWarningDistance(
                    distance,
                    direction,
                    object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            messageNotifier?.invoke(
                                Log.DEBUG,
                                "Set obstacle avoidance warning distance successfully for direction: ${direction.name}",
                                null
                            )
                            continuation.resume(true)
                        }

                        override fun onFailure(p0: IDJIError) {
                            messageNotifier?.invoke(
                                Log.ERROR,
                                "Set obstacle avoidance warning distance successfully for direction: ${direction.name}",
                                null
                            )
                            continuation.resume(false)
                        }
                    })
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
        suspendCancellableCoroutine {
            val callback = object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    TODO("Not yet implemented")
                }

                override fun onFailure(p0: IDJIError) {
                    TODO("Not yet implemented")
                }
            }
            if (enable) {
                VirtualStickManager.getInstance().enableVirtualStick(callback)
            } else {
                VirtualStickManager.getInstance().disableVirtualStick(callback)
            }
        }

    private fun changeVirtualStickStatus(
        enable: Boolean,
        syncAdvancedParam: Boolean,
        action: ((Boolean) -> Unit)?,
    ) {
        if (enable) {
            VirtualStickManager.getInstance()
                .enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        messageNotifier?.invoke(
                            Log.DEBUG,
                            "Enable virtual stick successfully",
                            null
                        )

                        if (syncAdvancedParam) VirtualStickManager.getInstance()
                            .setVirtualStickAdvancedModeEnabled(true)

                        action?.invoke(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier?.invoke(
                            Log.ERROR,
                            "Failed to enable the virtual stick(${p0.errorCode()}): ${p0.hint()}",
                            null
                        )
                        action?.invoke(false)
                    }
                })
        } else {
            if (syncAdvancedParam) VirtualStickManager.getInstance()
                .setVirtualStickAdvancedModeEnabled(false)

            VirtualStickManager.getInstance()
                .disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        messageNotifier?.invoke(
                            Log.DEBUG,
                            "Disable virtual stick successfully",
                            null
                        )
                        action?.invoke(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier?.invoke(
                            Log.ERROR,
                            "Failed to disable the virtual stick(${p0.errorCode()}): ${p0.hint()}",
                            null
                        )
                        action?.invoke(false)
                    }

                })
        }
    }

}