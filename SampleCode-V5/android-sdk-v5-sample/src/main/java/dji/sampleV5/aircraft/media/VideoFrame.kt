package dji.sampleV5.aircraft.media

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

class VideoFrame (data: ByteArray, val length: Int, val width: Int, val height: Int, val format: Int) {

    val buffer: ByteBuffer = ByteBuffer.allocateDirect(length)

    val counter = AtomicInteger(0)


    init {
        buffer.put(data, 0, length)
    }

    fun reference() {
        counter.incrementAndGet()
    }


    fun release() {
        if (counter.decrementAndGet() == 0) {
            buffer.clear()
        }
    }
}