package org.jason.testapp.android.stella.tracker

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

interface IMotionTracker<I, O> {

    fun feedFrame(input: I): O?

    fun getTrackingState(): TrackingState

    fun getCurrentPosition(): DoubleArray

    fun getCurrentRotation(): DoubleArray

    fun startup()

    fun shutdown()

    fun destroy()
}