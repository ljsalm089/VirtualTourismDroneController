package dji.sampleV5.aircraft
import java.nio.ByteBuffer
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import kotlin.math.max
import kotlin.math.min

object ConversionUtils {
    /**
     * Converts an ARGB32 ByteArray (in A, R, G, B order per pixel) to an I420Buffer.
     *
     * @param argbBuffer Input ARGB32 data.
     * @param width      Image width.
     * @param height     Image height.
     * @return           VideoFrame.I420Buffer for use in WebRTC.
     */
    fun convertARGB32ToI420(argbBuffer: ByteArray, width: Int, height: Int): VideoFrame.I420Buffer {
        // Allocate output planes.
        val yPlane = ByteArray(width * height)
        val uPlane = ByteArray((width * height) / 4)
        val vPlane = ByteArray((width * height) / 4)

        for (j in 0 until height) {
            for (i in 0 until width) {
                val index = j * width + i
                val argbIndex = index * 4

                // Extract components (assuming order: A, R, G, B)
                // Alpha is ignored in this conversion.
                val r = argbBuffer[argbIndex + 1].toInt() and 0xFF
                val g = argbBuffer[argbIndex + 2].toInt() and 0xFF
                val b = argbBuffer[argbIndex + 3].toInt() and 0xFF

                // BT.601 conversion.
                var y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                var u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                var v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                y = min(255, max(0, y))
                u = min(255, max(0, u))
                v = min(255, max(0, v))

                // Write Y value for every pixel.
                yPlane[index] = y.toByte()

                // For 4:2:0 subsampling, only write U and V once per 2x2 block.
                if (j % 2 == 0 && i % 2 == 0) {
                    val uvIndex = (j / 2) * (width / 2) + (i / 2)
                    uPlane[uvIndex] = u.toByte()
                    vPlane[uvIndex] = v.toByte()
                }
            }
        }

        // Wrap arrays in ByteBuffers.
        val yBuffer = ByteBuffer.wrap(yPlane)
        val uBuffer = ByteBuffer.wrap(uPlane)
        val vBuffer = ByteBuffer.wrap(vPlane)

        // Define strides: Y stride equals width, U and V strides are half the width.
        val strideY = width
        val strideU = width / 2
        val strideV = width / 2

        return JavaI420Buffer.wrap(
            width,
            height,
            yBuffer, strideY,
            uBuffer, strideU,
            vBuffer, strideV
        ) {
        }
    }
}
