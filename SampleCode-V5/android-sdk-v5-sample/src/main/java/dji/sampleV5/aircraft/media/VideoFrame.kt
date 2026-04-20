package dji.sampleV5.aircraft.media

import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class VideoFrame (data: ByteArray, val length: Int, val width: Int, val height: Int, val format:
Int, val frameTimeStampInSeconds: Double = 0.0) {

    val buffer: ByteBuffer = ByteBuffer.allocateDirect(length)

    val counter = AtomicInteger(0)


    init {
        buffer.put(data, 0, length)
    }

    fun reference() {
        counter.incrementAndGet()
    }


    fun release() {
        val referenceCount = counter.decrementAndGet()
        if (referenceCount == 0) {
            release(buffer)
        }
    }

    companion object {

        lateinit var cleanMethod: Method
        lateinit var clearMethod: Method

        init {
            try {
                val buffer = ByteBuffer.allocateDirect(1)

                cleanMethod = buffer.javaClass.getMethod("cleaner")
                cleanMethod.isAccessible = true

                val cleaner = cleanMethod.invoke(buffer)

                cleaner?.let {
                    clearMethod = cleaner.javaClass.getMethod("clean")
                    clearMethod.isAccessible = true

                    clearMethod.invoke(cleaner)
                }
            } catch (e: Exception) {

            }
        }

        fun release(buffer: ByteBuffer) {
            try {
                clearMethod.invoke(cleanMethod.invoke(buffer))
            } catch (e: Exception) {

            }
        }
    }
}