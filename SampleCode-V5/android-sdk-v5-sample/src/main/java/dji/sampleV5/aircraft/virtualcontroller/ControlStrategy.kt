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


fun clipVelocityForSafety(velocity: Double): Double {
    return if (abs(velocity) >= MAXIMUM_HORIZONTAL_VELOCITY) {
        if (velocity > 0) MAXIMUM_HORIZONTAL_VELOCITY else -MAXIMUM_HORIZONTAL_VELOCITY
    } else velocity
}

/**
 * assign velocities, target attitude, target height to the drone
 * @param forwardBackward specify the velocity in the direction of forward and back ward, positive value means going forward, negative value means going backward
 * @param rightLeft specify the velocity in the direction of right and left, positive value means going right, negative value means going left
 * @param downwardUpward specify the target height of the drone
 * @param targetAttitude specify the target attitude of the drone, null means no change
 */
internal fun adjustDroneVelocityOneTimeBodyBased(
    forwardBackward: Double = 0.0,
    rightLeft: Double = 0.0,
    downwardUpward: Double = 0.0,
    targetAttitude: Double? = 0.0,
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
 */
internal fun shortestAngle(originAngle: Double, targetAngle: Double): Double {
    return ((targetAngle - originAngle + 540) % 360 - 180)
}


internal fun Double.degreesToRadians() : Double {
    return this * (Math.PI / 180.0)
}