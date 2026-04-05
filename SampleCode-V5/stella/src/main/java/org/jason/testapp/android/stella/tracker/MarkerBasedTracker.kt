package org.jason.testapp.android.stella.tracker

import org.opencv.core.Mat

abstract class MarkerBasedTracker<I, O> : IMotionTracker<I, O> {

    private var trackerPtr: Long = 0L

    fun initialize(configurationFile: String): Boolean {
        if (0L == trackerPtr) {
            trackerPtr = createNativeObject(configurationFile)
        }

        return 0L != trackerPtr
    }

    protected fun feedFrame(input: Mat): Boolean {
        return nativeProcessFrame(trackerPtr, input.nativeObj)
    }

    override fun getTrackingState(): TrackingState {
        return TrackingState.from(nativeTrackingState())
    }

    external override fun getCurrentPosition(): DoubleArray

    external override fun getCurrentRotation(): DoubleArray

    external override fun startup()

    external override fun shutdown()

    external override fun destroy()

    private external fun createNativeObject(configFile: String): Long

    protected external fun nativeProcessFrame(trackerPtr: Long, framePtr: Long): Boolean

    private external fun nativeTrackingState(): Int

}