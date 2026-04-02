package org.jason.testapp.android.stella.tracker

abstract class MarkerBasedTracker<I, O> : IMotionTracker<I, O> {

    private var trackerPtr: Long = 0L

    fun initialize(configurationFile: String): Boolean {
        if (0L == trackerPtr) {
            trackerPtr = createNativeObject()
        }

        if (!nativeInitialize(configurationFile)) {
            destroy()
            trackerPtr = 0L
            return false
        }
        return true
    }

    override fun feedFrame(input: I): O? {
        TODO("Not yet implemented")
    }

    override fun getTrackingState(): TrackingState {
        return TrackingState.from(nativeTrackingState())
    }

    external override fun getCurrentPosition(): DoubleArray

    external override fun getCurrentRotation(): DoubleArray

    external override fun startup()

    external override fun shutdown()

    external override fun destroy()

    private external fun createNativeObject(): Long

    protected external fun nativeProcessFrame(trackerPtr: Long, framePtr: Long): Boolean

    private external fun nativeTrackingState(): Int

    private external fun nativeInitialize(filePath: String): Boolean
}