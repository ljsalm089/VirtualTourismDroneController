package dji.sampleV5.aircraft

import android.graphics.RectF
import android.util.Log


// if use the camera mounted on drone as the video source
var USE_DRONE_CAMERA = true


const val MAXIMUM_HORIZONTAL_VELOCITY = 0.2 // m/s


// if use the mock control class to test the control data sent from the headset
const val USE_MOCK_CONTROL = false


// the interval between sending two 'Ping' packet to test the data latency
const val PING_INTERVAL = 1000L

// the frequency of sending control command to the drone via the VirtualStick's advanced parameters
// maximum recommended frequency is 25 Hz
const val SENDING_FREQUENCY = 25


// the orientation data returned from drone is not accurate, need to be calibrated.
const val COMPASS_OFFSET = 0.toDouble()

// all log with level larger or equals to this level will be stored in log file
const val MINIMUM_LOG_LEVEL = Log.DEBUG


// use the button on screen to test the advanced parameter of drone control
const val TEST_VIRTUAL_STICK_ADVANCED_PARAM = true


// monitor the velocity changes actively or passively
const val MONITOR_VELOCITY_AND_ORIENTATION_ACTIVELY = false

// the maximum height the drone can reach while controlling via the thumb sticks
const val MAXIMUM_HEIGHT_FOR_THUMBSTICK_CONTROL = 2.0


// current valid electrical fence
val currentEFence = RectF(-10f, 10f, 10f, -10f)

// the movement of the headset will be applied to the drone after applying this scale
var HEADSET_MOVEMENT_SCALE = 0.8f

// velocity threshold of warning and ignore
val VELOCITY_THRESHOLD_OF_WARNING_AND_IGNORE = 2.0