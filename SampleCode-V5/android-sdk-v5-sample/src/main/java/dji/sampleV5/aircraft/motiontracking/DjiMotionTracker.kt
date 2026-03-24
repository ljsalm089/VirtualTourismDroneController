package dji.sampleV5.aircraft.motiontracking

import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.CoroutineDispatcher
import org.jason.testapp.android.stella.tracker.VSLamTracker
import org.opencv.core.Size

class DjiMotionTracker(val targetSize: Size, val ioDispatcher: CoroutineDispatcher) :
    VSLamTracker<ByteArray, Unit>(), ICameraStreamManager.CameraFrameListener {

    override fun initialize(
        configFilePath: String,
        vocabularyFilePath: String
    ): Boolean {
        return super.initialize(configFilePath, vocabularyFilePath)
    }

    override fun startup() {
        super.startup()
        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(ComponentIndexType.LEFT_OR_MAIN,
            ICameraStreamManager.FrameFormat.YUV420_888, this)
    }

    override fun shutdown() {
        super.shutdown()

        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(this)
    }

    override fun feedFrame(frame: ByteArray) {

    }

    override fun onFrame(
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        format: ICameraStreamManager.FrameFormat
    ) {
    }

}