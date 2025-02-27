package dji.sampleV5.aircraft

import android.content.Context
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

public class DJIVideoCapturer : VideoCapturer {

    private var capturerObserver: CapturerObserver? = null

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper?, applicationContext: Context?, capturerObserver: CapturerObserver?) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {

    }

    override fun stopCapture() {

    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {

    }

    override fun dispose() {

    }

    override fun isScreencast(): Boolean {
        return false
    }
}