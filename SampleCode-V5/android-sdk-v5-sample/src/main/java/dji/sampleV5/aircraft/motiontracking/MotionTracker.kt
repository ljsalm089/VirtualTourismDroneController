package dji.sampleV5.aircraft.motiontracking

import android.content.Context
import boofcv.abst.feature.detect.interest.PointDetectorTypes
import boofcv.abst.sfm.d3.MonocularPlaneVisualOdometry
import boofcv.factory.sfm.ConfigPlanarTrackPnP
import boofcv.factory.sfm.FactoryVisualOdometry
import boofcv.factory.tracker.ConfigPointTracker
import boofcv.io.calibration.CalibrationIO
import boofcv.struct.calib.CameraPinholeBrown
import boofcv.struct.calib.MonoPlaneParameters
import boofcv.struct.image.GrayU8
import boofcv.struct.pyramid.ConfigDiscreteLevels
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import georegression.struct.point.Vector3D_F64
import georegression.struct.se.Se3_F64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.internal.closeQuietly
import timber.log.Timber
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer


class MotionTracker(val scope: CoroutineScope, val dispatcher: CoroutineDispatcher) :
    ICameraStreamManager.CameraFrameListener {

    private var tmpScope: CoroutineScope? = null
    private var visualOdometry: MonocularPlaneVisualOdometry<GrayU8>? = null

    private val relatedPosition: Vector3D_F64 = Vector3D_F64()

    fun startMonitor(cameraParametersFile: File) {
        tmpScope = CoroutineScope(SupervisorJob() + dispatcher)

        val calibration = CalibrationIO.load<CameraPinholeBrown>(cameraParametersFile)

        calibrateAndStartMonitor(calibration.toMonoPlaneParameters())
    }

    private fun CameraPinholeBrown.toMonoPlaneParameters() : MonoPlaneParameters {
        val parameters = MonoPlaneParameters()
        parameters.intrinsic = this

        parameters.planeToCamera = Se3_F64()
        parameters.planeToCamera.T.z = 2.0

        return parameters
    }

    private fun calibrateAndStartMonitor(calibration: MonoPlaneParameters?) {
        val config = ConfigPlanarTrackPnP()

        config.tracker.typeTracker = ConfigPointTracker.TrackerType.KLT
        config.tracker.klt.pyramidLevels = ConfigDiscreteLevels.levels(4)
        config.tracker.klt.templateRadius = 3

        config.tracker.detDesc.detectPoint.type = PointDetectorTypes.SHI_TOMASI
        config.tracker.detDesc.detectPoint.general.maxFeatures = 600
        config.tracker.detDesc.detectPoint.general.radius = 3
        config.tracker.detDesc.detectPoint.general.threshold = 1.0f

        config.thresholdAdd = 75
        config.thresholdRetire = 2
        config.ransac.iterations = 200
        config.ransac.inlierThreshold = 1.5

        relatedPosition.zero()

        visualOdometry = FactoryVisualOdometry.monoPlaneInfinity(config, GrayU8::class.java)
        visualOdometry?.setCalibration(calibration)

        MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
            ComponentIndexType.LEFT_OR_MAIN,
            ICameraStreamManager.FrameFormat.YUV420_888, this
        )
    }

    fun startMonitor(context: Context) {
        val fs = context.assets.open("intrinsics.yaml")
        val calibration = CalibrationIO.load<CameraPinholeBrown>(InputStreamReader(fs))
        fs.closeQuietly()

        calibrateAndStartMonitor(calibration.toMonoPlaneParameters())
    }

    fun isTracking() = null != tmpScope

    fun stopMonitor() {
        tmpScope?.cancel()
        tmpScope = null
        relatedPosition.zero()

        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(this)
    }

    fun downscaleYPlane(
        videoFrame: ByteArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): GrayU8 {
        val buffer = ByteBuffer.wrap(videoFrame)
        buffer.position(0)
        buffer.limit(sourceWidth * sourceHeight)
        val yPlane = buffer.slice()

        val outData = ByteArray(targetWidth * targetHeight)

        // Calculate the scale factors
        val scaleX = sourceWidth.toFloat() / targetWidth
        val scaleY = sourceHeight.toFloat() / targetHeight

        for (y in 0 until targetHeight) {
            val srcY = (y * scaleY).toInt()
            yPlane.position(srcY * sourceWidth)
            yPlane.limit(sourceWidth)
            val rowBuffer = yPlane.slice()

            for (x in 0 until targetWidth) {
                val srcX = (x * scaleX).toInt()
                outData[y * targetWidth + x] = rowBuffer[srcX]
            }
        }

        return GrayU8(targetWidth, targetHeight).apply { data = outData }
    }

    fun processVideoFrame(
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
    ) {
        tmpScope?.launch(dispatcher) {
            visualOdometry?.let { odometry ->

                val grayU8 = downscaleYPlane(frameData, width, height, 1920, 1080)

                if (!odometry.process(grayU8)) {
                    Timber.e("Fail to process the video frame, try to store the old position and reset the visual odometry")
                    // TODO need to store the previous position and reset the visual odometry
                    relatedPosition.plusIP(odometry.cameraToWorld.T)

                    odometry.reset()
                }
            }
        }
    }

    fun currentPosition(): Vector3D_F64 {
        return relatedPosition.copy().plus(visualOdometry?.cameraToWorld?.T ?: Vector3D_F64())
    }

    override fun onFrame(
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        format: ICameraStreamManager.FrameFormat
    ) {
        processVideoFrame(frameData, offset, length, width, height)
    }
}