package dji.sampleV5.aircraft.virtualcontroller

import android.os.SystemClock
import dji.sampleV5.aircraft.HEADSET_MOVEMENT_SCALE
import dji.sampleV5.aircraft.VELOCITY_THRESHOLD_OF_WARNING_AND_IGNORE
import dji.sampleV5.aircraft.models.ControlStatusData
import dji.sampleV5.aircraft.models.Vector3D
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.utils.format
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
    param.roll = forwardBackward
    param.pitch = rightLeft
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

interface IControlStrategy {

    fun isVirtualStickNeeded(): Boolean

    fun isVirtualStickAdvancedParamNeeded(): Boolean

    fun onControllerStatusData(data: ControlStatusData)

    fun updateDroneSpatialPositionMonitor(monitor: IPositionMonitor?)
}


class ControlViaHeadset(
    private val updateVelocityInterval: Long,
    private val nedBased: Boolean = true,
) : IControlStrategy {

    init {
        Timber.d("Create control strategy for headset")
    }

    private var positionMonitor: IPositionMonitor? = null

    private var lastValidSampleTime = 0L

    private var lastSendCmdTimestamp = 0L

    private var benchmarkRotation: Vector3D? = null

    private var lastValidRotation: Vector3D? = null

    private var benchmarkPosition: Vector3D? = null

    private var lastValidPosition: Vector3D? = null

    override fun isVirtualStickNeeded(): Boolean = true

    override fun isVirtualStickAdvancedParamNeeded(): Boolean = true

    // how to implement the control?
    // base on two states in each packet to calculate the difference and apply this difference to the drone?
    // or base on two states (last applied state and currently received state) to calculate the difference?

    override fun onControllerStatusData(data: ControlStatusData) {
        // no valid position monitor, skip the control status data first
        positionMonitor?.let {
            // receiving old data, ignore it
            if (lastValidSampleTime >= data.sampleTimestamp) return

            // there is a maximum frequency limitation regarding sending data to the drone through the virtual stick advanced parameters
            // but the frequency of sampling data in headset is bigger than this valid, so we need to combine multi frames of data into one drone command
            val currentLocalTimestamp = SystemClock.elapsedRealtime()
            if (lastValidSampleTime > 0L && currentLocalTimestamp - this.lastSendCmdTimestamp <= updateVelocityInterval) {
                return
            }

            if (null == benchmarkRotation) {
                this.benchmarkRotation = data.benchmarkRotation
                this.lastValidRotation = data.currentRotation
            }

            if (null == benchmarkPosition) {
                this.benchmarkPosition = data.benchmarkPosition
                this.lastValidPosition = data.benchmarkPosition
            }

            val timeGapInSeconds = if (this.lastSendCmdTimestamp == 0L) {
                val gap = data.sampleTimestamp - data.benchmarkSampleTimestamp * 1.0
                if (gap == 0.0) {
                    this.lastValidSampleTime = data.sampleTimestamp
                    this.lastSendCmdTimestamp = currentLocalTimestamp
                    return@let
                }
                gap
            } else
                (currentLocalTimestamp - this.lastSendCmdTimestamp) / 1000.0

            Timber.d("Current time gap: $timeGapInSeconds")

            // calculate the target attitude in NED coordinate system
            // TODO for test, get rid of the rotation and moving upward/downward first
//             var targetOrientationInNED: Double? = null
            val targetOrientationInNED = calculateTargetAttitudeInNED(data, timeGapInSeconds, it)

            // right here, rather than setting the velocity in upward/downward direction, assign a target height to the drone
//            var targetAltitudeInNED: Double? = null
            val targetAltitudeInNED = calculateTargetAltitudeInNED(data, timeGapInSeconds, it)

            // calculate the velocities
            // in north-south, east-west and downward-upward directions
            // or
            // in forward-backward, right-left and downward-upward directions
            val velocities = calculateVelocities(
                lastValidPosition!!,
                data.currentPosition, timeGapInSeconds,
                data.currentRotation.y.toDouble(), it
            )

            if (abs(velocities[0]) > VELOCITY_THRESHOLD_OF_WARNING_AND_IGNORE
                || abs(velocities[1]) > VELOCITY_THRESHOLD_OF_WARNING_AND_IGNORE
                || abs(velocities[2]) > VELOCITY_THRESHOLD_OF_WARNING_AND_IGNORE
            ) {
                Timber.d("Velocity exceeds the maximum limitation (N/E/D): ${velocities[0]} / ${velocities[1]} / ${velocities[2]}")
                return@let
            }

            this.lastValidRotation = data.currentRotation
            this.lastValidPosition = data.currentPosition

            if (null != targetOrientationInNED || 0.0 != velocities[0] || 0.0 != velocities[1] || 0.0 != velocities[2] || targetAltitudeInNED != null) {
                if (nedBased) {
                    adjustDroneVelocityOneTimeNED(
                        velocities[0],
                        velocities[1],
                        targetOrientationInNED,
                        targetAltitudeInNED
                    )
                } else {
                    adjustDroneVelocityOneTimeBodyBased(
                        velocities[0],
                        velocities[1],
                        targetOrientationInNED,
                        targetAltitudeInNED
                    )
                }
            }

            // update gimbal angle, don't care about update in last time, only synchronize user's attitude to the gimbal
            // range from 0 to 360,
            // the value increases from 0 when user sets his head;
            // the value decreases from 360 when user rises this head
            // valid value range [0, 90] and [270, 360)
            val currentAngle = data.currentRotation.x
            val targetAngle =
                if (currentAngle >= 0 && currentAngle <= 90) {
                    -currentAngle
                } else if (currentAngle >= 270 && currentAngle < 360) {
                    360 - currentAngle
                } else {
                    null
                }
            val currentRollAngle = data.currentRotation.z
            val targetRollAngle = if (currentRollAngle >= 0 && currentRollAngle <= 90) {
                - currentRollAngle
            } else if (currentRollAngle >= 90 && currentRollAngle < 360) {
                360 - currentRollAngle
            } else {
                null
            }
            targetAngle?.let { riseSet ->
                targetRollAngle?.let { roll ->
                    adjustCameraOrientation(riseSet.toDouble(), roll.toDouble())
                }
            }

//            // update last valid sample time
            this.lastValidSampleTime = data.sampleTimestamp
            this.lastSendCmdTimestamp = currentLocalTimestamp
        }
    }

    private fun calculateVelocities(
        lastPosition: Vector3D,
        currentPosition: Vector3D,
        gapTimestamp: Double,
        headsetOrientation: Double,
        positionMonitor: IPositionMonitor,
    ): DoubleArray {
        val xyzVelocity = DoubleArray(3)

        // INFO in unity, the z axis means forward/backward, the x axis means right/left, and the y axis means upward/downward
        val xDistance = currentPosition.x - lastPosition.x
        val yDistance = currentPosition.z - lastPosition.z
        val zDistance = currentPosition.y - lastPosition.y

        Timber.d(
            "Position change in X/Y/Z: ${xDistance.format(4)} / ${yDistance.format(4)} / ${
                zDistance.format(
                    4
                )
            }"
        )

        val xVelocity = xDistance / gapTimestamp * HEADSET_MOVEMENT_SCALE
        val yVelocity = yDistance / gapTimestamp * HEADSET_MOVEMENT_SCALE
        val zVelocity = zDistance / gapTimestamp * HEADSET_MOVEMENT_SCALE

        xyzVelocity[0] = xVelocity
        xyzVelocity[1] = yVelocity
        xyzVelocity[2] = zVelocity

        Timber.log(
            LogLevel.VERBOSE_HEADSET_DRONE_VELOCITY_CHANGES,
            "Headset position changes from (${lastPosition.x.format(4)}, ${lastPosition.z.format(4)}) to (${
                currentPosition.x.format(
                    4
                )
            }, ${currentPosition.z.format(4)})"
        )
        Timber.log(
            LogLevel.VERBOSE_HEADSET_DRONE_VELOCITY_CHANGES,
            "Headset velocity is(X/Y): (${xyzVelocity[0].format(4)}, ${xyzVelocity[1].format(4)})"
        )

        return if (nedBased) {
            positionMonitor.convertCoordinateToNED(xyzVelocity)
        } else {
            positionMonitor.convertCoordinateToBody(xyzVelocity)
        }
    }

    private fun calculateTargetAltitudeInNED(
        data: ControlStatusData,
        timeGapInSeconds: Double,
        monitor: IPositionMonitor,
    ): Double? {
        return data.currentPosition.y.toDouble()
    }

    private fun calculateTargetAttitudeInNED(
        data: ControlStatusData,
        timeGapInSeconds: Double,
        monitor: IPositionMonitor,
    ): Double? {
        // both these angles are relative orientation, range from 0 to 360
        var targetAngleInSCS = data.getOrientationInSCS()
        val currentAngleInSCS = monitor.getOrientationInSCS()

        val shortestAngle = shortestAngleInSCS(currentAngleInSCS, targetAngleInSCS)
        // calculate if the difference between current angle and target one is too large
        // check if go around the shortest path, it still exceed the maximum rotation velocity

        // only log when changes need to be applied
        Timber.d(
            buildString {
                append("Drone current orientation: $currentAngleInSCS, ")
                append("Headset current orientation: $targetAngleInSCS, ")
                append("Shortest angle is: $shortestAngle, ")
            }
        )

        var targetOrientationInNED =
            monitor.convertOrientationToNED(targetAngleInSCS)

        Timber.d(
            buildString {
                append("Calculated target drone orientation: $targetAngleInSCS, ")
                append("Headset benchmark orientation: ${data.benchmarkRotation.y}, ")
                append("Headset current orientation: ${data.currentRotation.y}, ")
                append("Drone benchmark: ${monitor.getOrientationBenchmark()}, ")
                append("Target orientation in NED system: $targetOrientationInNED")
            }
        )
        return targetOrientationInNED
    }

    override fun updateDroneSpatialPositionMonitor(monitor: IPositionMonitor?) {
        this.positionMonitor = monitor
    }

}