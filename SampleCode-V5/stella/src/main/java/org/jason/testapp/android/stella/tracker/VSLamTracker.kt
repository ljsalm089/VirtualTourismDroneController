package org.jason.testapp.android.stella.tracker

import androidx.annotation.CallSuper
import org.opencv.core.Mat

enum class TrackingState(val value: Int) {
    Initializing(0),
    Tracking(1),
    Lost(2),
    Invalid(-1);

    companion object {
        fun from(value: Int): TrackingState {
            for (state in TrackingState.entries) {
                if (state.value == value) {
                    return state
                }
            }
            return Invalid
        }
    }
}

abstract class VSLamTracker<I, O> {


    protected var trackerPtr: Long = 0L

    @CallSuper
    open fun initialize(configFilePath: String, vocabularyFilePath: String): Boolean {
        if (0L != trackerPtr) {
            destroy()
        }

        trackerPtr = createNativeTracker(configFilePath, vocabularyFilePath)
        return 0L != trackerPtr
    }

    abstract fun feedFrame(frame: I): O?

    protected fun processFrame(frame: Mat) {
        nativeProcessFrame(trackerPtr, frame.nativeObj)
    }

    protected fun getProcessedFrame(): Mat {
        return Mat(nativeGetProcessedFrame(trackerPtr))
    }

    protected external fun nativeGetProcessedFrame(trackerPtr: Long): Long

    protected external fun relocalizeCameraPose(newPose: DoubleArray): Boolean

    fun getTrackingState(): TrackingState {
        return TrackingState.from(nativeTrackingState(trackerPtr))
    }

    fun getCurrentPosition(): DoubleArray {
        return getPositionAndRotation().slice(IntRange(0, 2)).toDoubleArray()
    }

    fun getCurrentRotation(): DoubleArray {
        return getPositionAndRotation().slice(IntRange(3, 5)).toDoubleArray()
    }

    external fun saveMapDatabase(dbFilepath: String): Boolean

    external fun loadMapDatabase(dbFilePath: String): Boolean

    external fun isMappingModuleEnabled(): Boolean

    external fun setMappingModule(enabled: Boolean)

    external fun isLoopDetectorEnabled(): Boolean

    external fun setLoopDetector(enabled: Boolean)

    @CallSuper
    open external fun startup()

    @CallSuper
    open external fun shutdown()

    @CallSuper
    open external fun destroy()

    private fun getTracker(): Long {
        return trackerPtr
    }

    private external fun nativeProcessFrame(trackerPtr: Long, framePtr: Long)

    private external fun nativeTrackingState(trackerPtr: Long): Int

    private external fun createNativeTracker(
        configFilePath: String,
        vocabularyFilePath: String
    ): Long

    private external fun getPositionAndRotation(): DoubleArray
}