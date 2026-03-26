package dji.sampleV5.aircraft.virtualcontroller

import dji.sampleV5.aircraft.MAXIMUM_HORIZONTAL_VELOCITY
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.et.action
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import timber.log.Timber
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

/**
 * assign velocities in four directions
 * @param roll  specify the velocity in the direction of north and south, positive value means going towards north, negative value means south
 * @param pitch specify the velocity in the direction of east and west, positive value means going towards east, negative value means west
 * @param yaw   specify the target attitude of the drone, null means keep its current attitude
 * @param height  specify the target height of the drone
 */
internal fun adjustDroneVelocityOneTimeNED(
    roll: Double? = null,
    pitch: Double? = null,
    yaw: Double? = null,
    height: Double? = null,
) {
    val param = initDroneAdvancedParam()
    if (null == yaw) {
        // no valid angle, convert to velocity mode and set velocity to 0 to avoid rotation
        param.yaw = 0.0
        param.yawControlMode = YawControlMode.ANGULAR_VELOCITY
    } else {
        param.yawControlMode = YawControlMode.ANGLE
        param.yaw = yaw
    }
    if (null == roll) {
        param.roll = 0.0
    } else {
        param.roll = roll
    }
    if (null == pitch) {
        param.pitch = 0.0
    } else {
        param.pitch = pitch
    }
    if (null == height) {
        param.verticalThrottle = 0.0
        param.verticalControlMode = VerticalControlMode.VELOCITY
    } else {
        param.verticalThrottle = height
        param.verticalControlMode = VerticalControlMode.POSITION
    }

    Timber.d("Sending advanced stick param to the drone: ${param.toJson()}")
    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
}

fun clipVelocityForSafty(velocity: Double) : Double {
    return if (abs(velocity) >= MAXIMUM_HORIZONTAL_VELOCITY) {
        if (velocity > 0) MAXIMUM_HORIZONTAL_VELOCITY else -MAXIMUM_HORIZONTAL_VELOCITY
    } else velocity
}

/**
 * assign velocities, target attitude, target height to the drone
 * @param forwardBackward specify the velocity in the direction of forward and back ward, positive value means going forward, negative value means going backward
 * @param rightLeft specify the velocity in the direction of right and left, positive value means going right, negative value means going left
 * @param targetAttitude specify the target attitude of the drone, null means no change
 * @param height specify the target height of the drone
 */
internal fun adjustDroneVelocityOneTimeBodyBased(
    forwardBackward: Double = 0.0,
    rightLeft: Double = 0.0,
    targetAttitude: Double? = 0.0,
    height: Double? = null,
) {
    val param = initDroneAdvancedParam()
    if (null == targetAttitude) {
        // no valid angle, convert to velocity mode and set velocity to 0 to avoid rotation
        param.yaw = 0.0
        param.yawControlMode = YawControlMode.ANGULAR_VELOCITY
    } else {
        param.yawControlMode = YawControlMode.ANGLE
        param.yaw = targetAttitude
    }
    param.roll = clipVelocityForSafty(forwardBackward)
    param.pitch = clipVelocityForSafty(rightLeft)
    param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
    if (null == height) {
        param.verticalThrottle = 0.0
        param.verticalControlMode = VerticalControlMode.VELOCITY
    } else {
        param.verticalThrottle = height
        param.verticalControlMode = VerticalControlMode.POSITION
    }

    Timber.d("Sending advanced stick param to the drone: ${param.toJson()}")
    VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
}

/**
 * adjust the angle of the gimbal; positive means rising the camera; negative means setting the camera, the angle should range from -90 to 90
 */
private fun adjustCameraOrientation(riseSet: Double, roll: Double) {
    val param = GimbalAngleRotation()
    // pitch: the camera will rise (positive) or set (negative)
    // roll: the camera will rotate counterclockwise (positive) and clockwise (negative)
    // yaw: the camera will rotate towards left (positive) and right (negative)
    param.pitch = riseSet
    param.roll = roll
    param.yaw = 0.0
    param.mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
    param.duration = 0.3

    KeyTools.createKey(GimbalKey.KeyRotateByAngle).action(param, { result ->
        Timber.d("Rotate the gimbal successfully: $riseSet")
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
 */
private fun shortestAngleInSCS(originAngle: Double, targetAngle: Double): Double {
    return ((targetAngle - originAngle + 540) % 360 - 180)
}