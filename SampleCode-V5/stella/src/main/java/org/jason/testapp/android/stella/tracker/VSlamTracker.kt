package org.jason.testapp.android.stella.tracker

import androidx.annotation.CallSuper
import org.opencv.core.Mat

abstract class VSlamTracker<I, O> : IMotionTracker<I, O> {

    protected var trackerPtr: Long = 0L

    @CallSuper
    open fun initialize(configFilePath: String, vocabularyFilePath: String): Boolean {
        if (0L != trackerPtr) {
            destroy()
        }

        trackerPtr = createNativeTracker(configFilePath, vocabularyFilePath)
        return 0L != trackerPtr
    }

    protected fun processFrame(frame: Mat, frameTimestampInSeconds: Double) {
        nativeProcessFrame(trackerPtr, frame.nativeObj, frameTimestampInSeconds)
    }

    protected fun getProcessedFrame(): Mat {
        return Mat(nativeGetProcessedFrame(trackerPtr))
    }

    protected external fun nativeGetProcessedFrame(trackerPtr: Long): Long

    protected external fun relocalizeCameraPose(newPose: DoubleArray): Boolean

    override fun getTrackingState(): TrackingState {
        return TrackingState.from(nativeTrackingState(trackerPtr))
    }

    @CallSuper
    override fun getCurrentPosition(): DoubleArray {
        return getPositionAndRotation().slice(IntRange(0, 2)).toDoubleArray()
    }

    @CallSuper
    override fun getCurrentRotation(): DoubleArray {
        return getPositionAndRotation().slice(IntRange(3, 5)).toDoubleArray()
    }

    external fun saveMapDatabase(dbFilepath: String): Boolean

    external fun loadMapDatabase(dbFilePath: String): Boolean

    external fun isMappingModuleEnabled(): Boolean

    external fun setMappingModule(enabled: Boolean)

    external fun isLoopDetectorEnabled(): Boolean

    external fun setLoopDetector(enabled: Boolean)

    @CallSuper
    external override fun startup()

    @CallSuper
    external override fun shutdown()

    @CallSuper
    external override fun destroy()

    private fun getTracker(): Long {
        return trackerPtr
    }

    private external fun nativeProcessFrame(trackerPtr: Long, framePtr: Long,
                                            frameTimestampInSeconds: Double)

    private external fun nativeTrackingState(trackerPtr: Long): Int

    private external fun createNativeTracker(
        configFilePath: String, vocabularyFilePath: String
    ): Long

    private external fun getPositionAndRotation(): DoubleArray
}