package org.jason.testapp.android.stella.tracker

import android.os.SystemClock
import org.opencv.android.CameraBridgeViewBase
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class Camera2VisionTracker (val targetSize: Size, val drawPoints: Boolean) :
    VSlamTracker<CameraBridgeViewBase.CvCameraViewFrame, Mat>() {

    private var startTimeStamp: Long = 0L

    private val resizedMat = Mat()

    private var resizeProcessedFrame: Mat? = null

    override fun feedFrame(frame: CameraBridgeViewBase.CvCameraViewFrame): Mat? {
        if (0L == startTimeStamp) startTimeStamp = SystemClock.elapsedRealtime()

        val grayMat = frame.gray()

        if (drawPoints) {
            if (resizeProcessedFrame == null) {
                resizeProcessedFrame = Mat(grayMat.size(), grayMat.type())
            }
        }
        Imgproc.resize(grayMat, resizedMat, targetSize)

        processFrame(resizedMat, (SystemClock.elapsedRealtime() - startTimeStamp) / 1000.0)

        if (!drawPoints) {
            return null
        }

        val processedMat = getProcessedFrame()
        Imgproc.resize(processedMat, resizeProcessedFrame!!, grayMat.size())
        processedMat.release()
        return resizeProcessedFrame
    }

    override fun destroy() {
        super.destroy()
        resizedMat.release()
        resizeProcessedFrame?.release()
        resizeProcessedFrame = null
    }
}