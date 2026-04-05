package dji.sampleV5.aircraft.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

class DronePhotoCapturer(
    val scope: CoroutineScope,
    val dispatcher: CoroutineDispatcher,
    val filePrefixName: String,
    val targetDir: File
) : VideoFrameListener {

    var photoIndex = 0

    var deferred: CompletableDeferred<String?>? = null


    fun startup() {
        VideoManager.instance.subscribe(this)
    }

    fun stop() {
        VideoManager.instance.unsubscribe(this)
    }

    suspend fun capture(): String? {
        val deferred = CompletableDeferred<String?>()
        this.deferred = deferred

        return deferred.await()
    }

    override fun onVideoFrame(frame: VideoFrame) {
        deferred?.let {
            frame.reference()
            scope.launch(dispatcher) {
                try {
                    val fileName = saveFrameToImage(frame)
                    it.complete(fileName)
                } finally {
                    frame.release()
                }
            }
        }
        deferred = null
    }

    private fun saveFrameToImage(frame: VideoFrame): String {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val fileName = "${filePrefixName}_${photoIndex++}.jpg"
        val file = File(targetDir, fileName)

        // I420 format: Y plane, then U plane, then V plane.
        // Total height for Mat is height * 1.5
        val yuvMat = Mat((frame.height * 1.5).toInt(), frame.width, CvType.CV_8UC1, frame.buffer)
        val bgrMat = Mat()
        Imgproc.cvtColor(yuvMat, bgrMat, Imgproc.COLOR_YUV2BGR_I420)

        Imgcodecs.imwrite(file.absolutePath, bgrMat)

        yuvMat.release()
        bgrMat.release()

        return file.absolutePath
    }
}
