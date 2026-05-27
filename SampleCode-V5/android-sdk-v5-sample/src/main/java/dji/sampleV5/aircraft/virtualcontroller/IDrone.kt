package dji.sampleV5.aircraft.virtualcontroller

import android.util.Log
import dji.sampleV5.aircraft.utils.format
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.get
import dji.v5.et.set
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

interface IDrone {

    suspend fun isDroneFlying(): Boolean

    suspend fun landOff(): Boolean

    suspend fun getReady(): Boolean

    suspend fun updateObstacleAvoidanceWarningDistance(distance: Double): Boolean

    suspend fun updateObstacleAvoidance(enable: Boolean): Boolean

    suspend fun setGimbalModel(allFree: Boolean): Boolean

    suspend fun setHeightLimit(maximumHeight: Int): Boolean

    suspend fun setAutomaticControl(enable: Boolean): Boolean

    /**
     * assign velocities, target attitude, target height to the drone
     * @param forwardBackward specify the velocity in the direction of forward and back ward, positive value means going forward, negative value means going backward
     * @param rightLeft specify the velocity in the direction of right and left, positive value means going right, negative value means going left
     * @param upwardDownward specify the velocity in the direction of upward and downward, positive
     * value means going down, negative value means going up
     * @param targetYawAngle specify the target attitude of the drone, null means no change
     */
    suspend fun adjustDroneVelocityOneTime(
        forwardBackward: Double = 0.0,
        rightLeft: Double = 0.0,
        upwardDownward: Double = 0.0,
        targetYawAngle: Double? = null
    )

    /**
     * adjust the angle of the gimbal
     *
     * @param pitch positive means rising the camera; negative means setting the camera, the angle should range from -90 to 90
     * @param roll positive means rotating the camera counterclockwise; negative means rotating the camera clockwise
     */
    suspend fun adjustCameraOrientation(pitch: Double, roll: Double, duration: Double)

    /**
     * clear all the velocities in all directions, make the drone stay still
     */
    suspend fun reset()

    fun destroy()
}

class DjiDrone(
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
    val rawDataObservable: RawDataObservable,
    val controlStatusFeedback: ControlStatusFeedback? = null,
    val messageNotifier: MessageNotifier? = null
) : IDrone {

    private val expectedTakeOffHeight = 1.2f

    fun hasReachedTargetHeight(current: Double, target: Double): Boolean {
        return abs(current - target) <= abs(target / 10f)
    }

    override suspend fun getReady(): Boolean {
        if (!isDroneFlying()) {
            // the drone is not flying, take the drone off and make float in a specific height
            if (!takeOff()) {
                return false
            }

            delay(100)
            // detect if the drone has reached the target height
            while (!hasReachedTargetHeight(getDroneHeight(), expectedTakeOffHeight.toDouble())) {
                delay(100)
            }
            return true
        }
        // already flying, take current position as the initial one
        return true
    }

    override suspend fun isDroneFlying(): Boolean = suspendCancellableCoroutine { continuation ->
        KeyTools.createKey(FlightControllerKey.KeyIsFlying).get({ flying ->
            flying?.let {
                continuation.resume(it) { cause, _, _ -> null?.let { it1 -> it1(cause) } }
            } ?: {
                continuation.resumeWithException(Exception("Unable to check if drone is flying"))
                message(Log.ERROR, "Unable to check if drone is flying")
            }
            continuation
        }, {
            val msg = "Unable to check if drone is flying(${it.errorCode()}): ${it.hint()}"
            continuation.resumeWithException(Exception(msg))
            message(Log.ERROR, msg)
        })
    }

    suspend fun getDroneHeight(): Double = suspendCancellableCoroutine { continuation ->
        val ultrasonicHeightKey = FlightControllerKey.KeyUltrasonicHeight

        val callback = object : OnRawDataObserver {
            override fun invoke(p1: DJIKeyInfo<*>, p2: Any?) {
                if (p1.innerIdentifier == ultrasonicHeightKey.innerIdentifier && p2 is Int) {
                    val height: Double = p2 / 10.0

                    rawDataObservable.unregister(ultrasonicHeightKey, this)
                    continuation.resume(height)
                }
            }
        }
        rawDataObservable.register(ultrasonicHeightKey, callback)

        continuation.invokeOnCancellation {
            rawDataObservable.unregister(
                ultrasonicHeightKey,
                callback
            )
        }
    }

    override suspend fun landOff(): Boolean = suspendCancellableCoroutine { continuation ->
        KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding).action({
            continuation.resume(true)
        }, { error ->
            continuation.resume(false)
            message(
                Log.ERROR,
                "Unable to land off the drone (${error.errorCode()}): ${error.hint()}",
            )
        })
    }

    suspend fun takeOff(): Boolean = suspendCancellableCoroutine { continuation ->
        KeyTools.createKey(FlightControllerKey.KeyStartTakeoff).action({
            continuation.resume(true)
        }, {
            continuation.resume(false)
            message(
                Log.ERROR,
                "Unable to take off the drone (${it.errorCode()}): ${it.hint()}",
            )
        })
    }

    override suspend fun updateObstacleAvoidanceWarningDistance(distance: Double): Boolean =
        suspendCancellableCoroutine { continuation ->
            // only callback after three settings are successful, or one of them is fail
            val callback: CommonCallbacks.CompletionCallback =
                object : CommonCallbacks.CompletionCallback {
                    val callbackCount = AtomicInteger(0)

                    override fun onSuccess() {
                        if (callbackCount.incrementAndGet() == 3
                            && !continuation.isCompleted
                            && continuation.isActive
                            && !continuation.isCancelled
                        ) {
                            continuation.resume(true)
                            message(msg = "Update obstacle avoidance warning distances successfully")
                        }
                    }

                    override fun onFailure(p0: IDJIError) {
                        if (!continuation.isCompleted && continuation.isActive && !continuation.isCancelled) {
                            continuation.resume(false)
                            message(
                                Log.ERROR,
                                "Unable to update the obstacle avoidance warning distance (${p0.errorCode()}): ${p0.hint()}",
                            )
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

    override suspend fun updateObstacleAvoidance(enable: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            // right here, can use the `setObstacleAvoidanceEnabled` to enable/disable obstacle avoidance in three directions,
            // because the mini 3 pro does not support this kind of operation
            PerceptionManager.getInstance().setObstacleAvoidanceType(
                if (enable) ObstacleAvoidanceType.BYPASS else ObstacleAvoidanceType.CLOSE,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        message(
                            Log.DEBUG,
                            "${if (enable) "Enable" else "Disable"} obstacle avoidance successfully",
                        )
                        continuation.resume(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        message(
                            Log.ERROR,
                            "${if (enable) "Enable" else "Disable"} obstacle avoidance failed (${p0.errorCode()}): ${p0.hint()}",
                        )
                        continuation.resume(false)
                    }
                })
        }

    override suspend fun setGimbalModel(allFree: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            KeyTools.createKey(GimbalKey.KeyGimbalMode)
                .set(
                    if (allFree) GimbalMode.FPV else GimbalMode
                        .YAW_FOLLOW, {
                        message(Log.DEBUG, "Set gimbal mode successfully")
                        continuation.resume(true)
                    }, {
                        message(
                            Log.ERROR,
                            "Failed to set gimbal mode (${it.errorCode()}): ${it.hint()}"
                        )
                        continuation.resume(false)
                    })
        }

    override suspend fun setHeightLimit(maximumHeight: Int): Boolean =
        suspendCancellableCoroutine { continuation ->
            // set maximum height the drone can fly
            KeyTools.createKey(FlightControllerKey.KeyHeightLimit).set(maximumHeight, {
                message(
                    Log.DEBUG,
                    "Set the maximum height of drone successfully"
                )
                continuation.resume(true)
            }, {
                message(
                    Log.ERROR,
                    "Failed to set maximum height of drone (${it.errorCode()}): ${it.hint()}"
                )
                continuation.resume(false)
            })
        }

    override suspend fun setAutomaticControl(enable: Boolean): Boolean {
        if (!enable) setVirtualStickAdvancedModel(false)

        if (setVirtualStickModel(enable)) {
            if (enable) setVirtualStickAdvancedModel(true)
            return true
        } else {
            return false
        }
    }

    fun setVirtualStickAdvancedModel(enable: Boolean) {
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(enable)
    }

    suspend fun setVirtualStickModel(enable: Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    continuation.resume(true)
                    val msg = "change virtual stick model to $enable successfully"
                    message(Log.INFO, msg)
                    Timber.i(msg)
                }

                override fun onFailure(p0: IDJIError) {
                    continuation.resume(false)
                    val msg = "change virtual stick model to $enable fail (${p0.errorCode()}): ${p0.hint()}"
                    message(Log.ERROR, msg)
                    Timber.e(msg)
                }
            }
            if (enable) {
                VirtualStickManager.getInstance().enableVirtualStick(callback)
            } else {
                VirtualStickManager.getInstance().disableVirtualStick(callback)
            }
        }

    override suspend fun adjustDroneVelocityOneTime(
        forwardBackward: Double,
        rightLeft: Double,
        upwardDownward: Double,
        targetYawAngle: Double?
    ) {
        if (0.0 == forwardBackward && 0.0 == rightLeft && 0.0 == upwardDownward && null == targetYawAngle) {
            return
        }
        Timber.d(
            "forward: ${forwardBackward.format(5)} right: ${rightLeft.format(5)} up: ${
                upwardDownward.format(
                    5
                )
            }"
        )
        val param = initDroneAdvancedParam()
        if (null == targetYawAngle) {
            // no valid angle, convert to velocity mode and set velocity to 0 to avoid rotation
            param.yaw = 0.0
            param.yawControlMode = YawControlMode.ANGULAR_VELOCITY
        } else {
            param.yawControlMode = YawControlMode.ANGLE
            param.yaw = targetYawAngle
        }

        param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
        param.rollPitchControlMode = RollPitchControlMode.VELOCITY
        param.roll = clipVelocityForSafety(forwardBackward)
        param.pitch = clipVelocityForSafety(rightLeft)

        param.verticalControlMode = VerticalControlMode.VELOCITY
        param.verticalThrottle = clipVelocityForSafety(upwardDownward)

        Timber.d("Sending advanced stick param to the drone: ${param.toJson()}")
        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    override suspend fun adjustCameraOrientation(
        pitch: Double,
        roll: Double,
        duration: Double
    ) {
        val param = GimbalAngleRotation()
        // pitch: the camera will rise (positive) or set (negative)
        // roll: the camera will rotate counterclockwise (positive) and clockwise (negative)
        // yaw: the camera will rotate towards left (positive) and right (negative)
        param.pitch = pitch
        param.roll = roll
        param.yaw = 0.0
        param.mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
        param.duration = duration

        KeyTools.createKey(GimbalKey.KeyRotateByAngle).action(param, { result ->
            Timber.d("Rotate the gimbal successfully: $pitch")
        }, { error ->
            Timber.e("Failed to rotate the gimbal (${error.errorCode()}): ${error.hint()}")
        })
    }

    override suspend fun reset() {
        adjustDroneVelocityOneTime(0.0, 0.0, 0.0, null)
    }

    override fun destroy() {
        // any specific resource need to be cleaned ???
    }

    private fun message(level: Int = Log.INFO, msg: String = "", error: Throwable? = null) {
        messageNotifier?.invoke(level, msg, error)
    }
}