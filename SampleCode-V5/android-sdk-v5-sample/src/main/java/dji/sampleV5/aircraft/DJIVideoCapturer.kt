package dji.sampleV5.aircraft

import android.content.Context
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

class DJIVideoCapturer : VideoCapturer {

    var isCapturing = false
        private set

    var capturerObserver: CapturerObserver? = null
        private set

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper?, applicationContext: Context?, capturerObserver: CapturerObserver?) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        isCapturing = true
    }

    override fun stopCapture() {
        isCapturing = false
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {

    }

    override fun dispose() {
        capturerObserver = null
    }

    override fun isScreencast(): Boolean {
        return false
    }
}