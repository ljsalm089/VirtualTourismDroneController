package dji.sampleV5.aircraft.virtualcontroller

import android.util.Log
import dji.sampleV5.aircraft.MAXIMUM_HORIZONTAL_VELOCITY
import dji.sampleV5.aircraft.utils.format
import dji.sdk.keyvalue.key.DJICameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.sdk.keyvalue.value.gimbal.GimbalResetType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.et.set
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.abs


internal fun initDroneAdvancedParam(): VirtualStickFlightControlParam {
    val param = VirtualStickFlightControlParam()
    param.yaw = 0.0
    param.roll = 0.0
    param.pitch = 0.0
    param.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
    param.verticalControlMode = VerticalControlMode.VELOCITY
    param.yawControlMode = YawControlMode.ANGLE
    param.rollPitchControlMode = RollPitchControlMode.VELOCITY
    return param
}


fun clipVelocityForSafety(velocity: Double): Double {
    return if (abs(velocity) >= MAXIMUM_HORIZONTAL_VELOCITY) {
        if (velocity > 0) MAXIMUM_HORIZONTAL_VELOCITY else -MAXIMUM_HORIZONTAL_VELOCITY
    } else velocity
}

/**
 * assign velocities, target attitude, target height to the drone
 * @param forwardBackward specify the velocity in the direction of forward and back ward, positive value means going forward, negative value means going backward
 * @param rightLeft specify the velocity in the direction of right and left, positive value means going right, negative value means going left
 * @param downwardUpward specify the velocity in the direction of upward and downward, positive
 * value means going down, negative value means going up
 * @param targetAttitude specify the target attitude of the drone, null means no change
 */
internal fun adjustDroneVelocityOneTimeBodyBased(
    forwardBackward: Double = 0.0,
    rightLeft: Double = 0.0,
    downwardUpward: Double = 0.0,
    targetAttitude: Double? = 0.0,
) {
    if (0.0 == forwardBackward && 0.0 == rightLeft && 0.0 == downwardUpward && null == targetAttitude) {
        return
    }
    Timber.d("forward: ${forwardBackward.format(5)} right: ${rightLeft.format(5)} down: ${downwardUpward.format(5)}")
    val param = initDroneAdvancedParam()
    if (null == targetAttitude) {
        // no valid angle, convert to velocity mode and set velocity to 0 to avoid rotation
        param.yaw = 0.0
        param.yawControlMode = YawControlMode.ANGULAR_VELOCITY
    } else {
        param.yawControlMode = YawControlMode.ANGLE
        param.yaw = targetAttitude
    }

    param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
    param.rollPitchControlMode = RollPitchControlMode.VELOCITY
    param.roll = clipVelocityForSafety(forwardBackward)
    param.pitch = clipVelocityForSafety(rightLeft)

    param.verticalControlMode = VerticalControlMode.VELOCITY
    param.verticalThrottle = clipVelocityForSafety(downwardUpward)

    Timber.d("Sending advanced stick param to the drone: ${param.toJson()}")
    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
}

/**
 * adjust the angle of the gimbal
 *
 * @param pitch positive means rising the camera; negative means setting the camera, the angle should range from -90 to 90
 * @param roll positive means rotating the camera counterclockwise; negative means rotating the camera clockwise
 */
internal fun adjustCameraOrientation(pitch: Double, roll: Double, duration: Double) {
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

/**
 * calculate the shortest angle starts from the original angle to the target angle.
 * positive means change in clockwise direction; negative means change in counterclockwise direction
 *
 * @param originAngle the original angle
 * @param targetAngle the target angle
 *
 * @return range from -180 to 180
 */
internal fun shortestAngle(originAngle: Double, targetAngle: Double): Double {
    return ((targetAngle - originAngle + 540) % 360 - 180)
}


internal fun Double.degreesToRadians() : Double {
    return this * (Math.PI / 180.0)
}



internal suspend fun setObstacleAvoidanceWarningDistance(distance: Double, messageNotifier: MessageNotifier? = null): Boolean =
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

internal suspend fun setObstacleAvoidance(enable: Boolean, messageNotifier: MessageNotifier? = null): Boolean =
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

internal suspend fun setGimbalMode(allFree: Boolean, messageNotifier: MessageNotifier? = null): Boolean = suspendCancellableCoroutine { continuation ->
    KeyTools.createKey(GimbalKey.KeyGimbalMode).set(if (allFree) GimbalMode.YAW_FOLLOW else GimbalMode.FPV, {
        messageNotifier?.invoke(Log.DEBUG, "Set gimbal mode successfully", null)
        continuation.resume(true)
    }, {
        messageNotifier?.invoke(Log.ERROR, "Failed to set gimbal mode (${it.errorCode()}): ${it.hint()}", null)
        continuation.resume(false)
    })
}

internal suspend fun resetGimbalAngles(messageNotifier: MessageNotifier? = null): Boolean = suspendCancellableCoroutine { continuation ->
    val callbackCount = AtomicInteger(0)

    val onSuccess = {
        messageNotifier?.invoke(Log.DEBUG, "Reset Gimbal successfully", null)
        if (callbackCount.incrementAndGet() == 2
            && !continuation.isCompleted
            && continuation.isActive
            && !continuation.isCancelled) {
            continuation.resume(true)
        }
    }

    val onError:((e: IDJIError)-> Unit) =  { error->
        messageNotifier?.invoke(Log.ERROR, "Failed to reset gimbal: (${error.errorCode()}): ${error.hint()}", null)
        if (!continuation.isCompleted && continuation.isActive && !continuation.isCancelled) {
            continuation.resume(false)
        }
    }
    GimbalKey.KeyGimbalReset.create().set(GimbalResetType.PITCH_YAW, onSuccess, onError)
    GimbalKey.KeyGimbalReset.create().set(GimbalResetType.ONLY_ROLL, onSuccess, onError)
}

internal suspend fun resetGimbal(messageNotifier: MessageNotifier? = null): Boolean = suspendCancellableCoroutine { continuation ->
    val attitude = Attitude(0.0, 0.0, 0.0)
    KeyTools.createKey(GimbalKey.KeyGimbalAttitude).set(
        attitude, {
            messageNotifier?.invoke(Log.DEBUG, "Reset the gimbal successfully", null)
            continuation.resume(true)
        }, {
            messageNotifier?.invoke(Log.DEBUG, "Failed to reset the gimbal: (${it.errorCode()}): ${it.hint()}", null)
            continuation.resume(false)
        }
    )
}

internal suspend fun setHeightLimit(limit: Int, messageNotifier: MessageNotifier? = null): Boolean =
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

internal suspend fun changeVirtualStickStatus(enable: Boolean, messageNotifier: MessageNotifier? = null): Boolean =
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