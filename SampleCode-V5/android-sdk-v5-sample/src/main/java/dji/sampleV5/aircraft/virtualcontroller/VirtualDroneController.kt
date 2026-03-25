package dji.sampleV5.aircraft.virtualcontroller

import android.util.Log
import dji.sampleV5.aircraft.SENDING_FREQUENCY
import dji.sampleV5.aircraft.models.ControlStatusData
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.utils.toJson
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
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
import kotlinx.coroutines.cancel
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
typealias StatusUpdater = (String, String) -> Unit


interface IDroneController {

    suspend fun prepareDrone(controlMode: Int)

    suspend fun abort()

    suspend fun landOff()

    fun changeDroneVelocity(
        forwardBackward: Double = 0.0,
        rightLeft: Double = 0.0,
        rotateRightLeft: Double = 0.0,
        period: Long = 1000,
    )

    fun riseAndSetGimbal(angle: Double)

    fun changeDroneVelocityBaseOnGround(
        northAndSouth: Double = 0.0,
        eastAndWest: Double = 0.0,
        rotateRightLeft: Double = 0.0,
        period: Long = 1000,
    )

    suspend fun onControllerStatusData(data: ControlStatusData)

    suspend fun destroy()

    fun isDroneReady(): Boolean

}

abstract class BaseDroneController(
    protected val scope: CoroutineScope,
    protected val observable: RawDataObservable,
    protected val controlStatusFeedback: ControlStatusFeedback?,
    protected val messageNotifier: MessageNotifier?,
    protected val statusUpdater: StatusUpdater?,
) : IDroneController {

    private var isReady: Boolean = false

    override fun isDroneReady(): Boolean {
        return isReady
    }

    protected open fun switchDroneStatus(isReady: Boolean) {
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
    observable: RawDataObservable,
    controlStatusFeedback: ControlStatusFeedback?,
    messageNotifier: MessageNotifier?,
    statusUpdater: StatusUpdater?,
) : BaseDroneController(scope, observable, controlStatusFeedback, messageNotifier, statusUpdater) {

    private val SESSION_ID = UUID.randomUUID().toString()

    override suspend fun prepareDrone(controlMode: Int) {
        Timber.d("Mock to start the control")
        switchDroneStatus(true)
    }

    override suspend fun abort() {
        Timber.d("Mock to abort the control")
        switchDroneStatus(false)
    }

    override fun changeDroneVelocity(
        forwardBackward: Double,
        rightLeft: Double,
        rotateRightLeft: Double,
        period: Long,
    ) {
        Timber.d("Mock to change drone velocity: $forwardBackward / $rightLeft / $rotateRightLeft / $period")
    }

    override fun changeDroneVelocityBaseOnGround(
        northAndSouth: Double,
        eastAndWest: Double,
        rotateRightLeft: Double,
        period: Long,
    ) {
        Timber.d("Mock to change drone velocity: $northAndSouth / $eastAndWest / $rotateRightLeft / $period")
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
    observable: RawDataObservable,
    controlStatusFeedback: ControlStatusFeedback?,
    messageNotifier: MessageNotifier?,
    statusUpdater: StatusUpdater?,
) : BaseDroneController(scope, observable, controlStatusFeedback, messageNotifier, statusUpdater) {

    private val expectedTakeOffHeight = 1.2f

    private var droneParam: VirtualStickFlightControlParam

    private var sendingCmdJob: Job? = null

    private var controlStrategy: IControlStrategy? = null

    private var positionMonitor: IPositionMonitor? = null

    init {
        setObstacleAvoidanceWarningDistance(0.1)
        setObstacleAvoidance(false, null)

        // set maximum height the drone can fly
        KeyTools.createKey(FlightControllerKey.KeyHeightLimit).set(2, {
            messageNotifier?.invoke(Log.DEBUG, "Set the maximum height of drone successfully", null)
        }, {
            messageNotifier?.invoke(
                Log.ERROR,
                "Failed to set maximum height of drone (${it.errorCode()}): ${it.hint()}",
                null
            )
        })

        droneParam = initDroneAdvancedParam()
        // TODO just for test
        droneParam.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
    }

    override fun switchDroneStatus(isReady: Boolean) {
        if (isReady) {
            positionMonitor?.stop()

            // TODO needed to be refactored
            positionMonitor = if (true == controlStrategy?.isVirtualStickAdvancedParamNeeded())
                DroneSpatialPositionMonitor(
                    observable, statusUpdater
                )
            else
                DroneSpatialPositionMonitor(observable, statusUpdater)

            if (true == controlStrategy?.isVirtualStickNeeded()) {
                positionMonitor?.start()
                changeVirtualStickStatus(
                    enable = true,
                    syncAdvancedParam = true == controlStrategy?.isVirtualStickAdvancedParamNeeded()
                ) {
                    if (it) {
                        controlStrategy?.updateDroneSpatialPositionMonitor(positionMonitor!!)
                        super.switchDroneStatus(isReady)
                    } else {
                        positionMonitor?.stop()
                    }
                }
            } else {
                // this should not happen
                positionMonitor?.start()
                controlStrategy?.updateDroneSpatialPositionMonitor(null)
                super.switchDroneStatus(true)
            }
        } else {
            super.switchDroneStatus(false)
            adjustDroneVelocityOneTimeNED(0.0, 0.0, null)
            changeVirtualStickStatus(enable = false, syncAdvancedParam = true, null)
            positionMonitor?.stop()
            positionMonitor = null
        }
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
            // TODO update the received position and rotation to the control strategy
            controlStrategy?.onControllerStatusData(data)
        }
    }

    override suspend fun prepareDrone(controlMode: Int) {
        controlStrategy = ControlViaHeadset(1000L / SENDING_FREQUENCY, true)

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

    override fun changeDroneVelocity(
        forwardBackward: Double,
        rightLeft: Double,
        rotateRightLeft: Double,
        period: Long,
    ) {
        adjustDroneVelocity(forwardBackward, rightLeft, rotateRightLeft)

        if (period <= 0) return

        scope.launch(Dispatchers.Main) {
            delay(period)

            adjustDroneVelocity()
        }
    }

    override fun changeDroneVelocityBaseOnGround(
        northAndSouth: Double,
        eastAndWest: Double,
        rotateRightLeft: Double,
        period: Long,
    ) {
        adjustDroneVelocity(northAndSouth, eastAndWest, rotateRightLeft)

        if (period <= 0) return

        scope.launch(Dispatchers.Main) {
            delay(period)

            adjustDroneVelocity()
        }
    }


    override suspend fun abort() {
        switchDroneStatus(false)
    }

    override suspend fun landOff() {
        Timber.d("Start to land off the drone.")
        KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding).action()
    }

    override suspend fun destroy() {
        setObstacleAvoidance(true, null)
        setObstacleAvoidanceWarningDistance(4.0)

        changeVirtualStickStatus(enable = false, syncAdvancedParam = true, null)
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

    private fun setObstacleAvoidanceWarningDistance(distance: Double) {
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
                    }

                    override fun onFailure(p0: IDJIError) {
                        messageNotifier?.invoke(
                            Log.ERROR,
                            "Set obstacle avoidance warning distance successfully for direction: ${direction.name}",
                            null
                        )
                    }
                })
        }
    }

    private fun setObstacleAvoidance(enable: Boolean, action: (() -> Unit)?) {
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
                    action?.invoke()
                }

                override fun onFailure(p0: IDJIError) {
                    messageNotifier?.invoke(
                        Log.ERROR,
                        "${if (enable) "Enable" else "Disable"} obstacle avoidance failed (${p0.errorCode()}): ${p0.hint()}",
                        null
                    )
                }
            })
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