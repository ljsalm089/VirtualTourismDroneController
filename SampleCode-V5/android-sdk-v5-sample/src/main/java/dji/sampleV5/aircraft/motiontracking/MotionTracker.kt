package dji.sampleV5.aircraft.motiontracking

import boofcv.abst.feature.detect.interest.PointDetectorTypes
import boofcv.abst.sfm.d3.MonocularPlaneVisualOdometry
import boofcv.factory.sfm.ConfigPlanarTrackPnP
import boofcv.factory.sfm.FactoryVisualOdometry
import boofcv.factory.tracker.ConfigPointTracker
import boofcv.io.calibration.CalibrationIO
import boofcv.struct.calib.MonoPlaneParameters
import boofcv.struct.image.GrayU8
import boofcv.struct.pyramid.ConfigDiscreteLevels
import georegression.struct.point.Vector3D_F64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer


class MotionTracker(val scope: CoroutineScope, val dispatcher: CoroutineDispatcher) {

    private var tmpScope: CoroutineScope? = null
    private var visualOdometry: MonocularPlaneVisualOdometry<GrayU8>? = null

    private val relatedPosition: Vector3D_F64 = Vector3D_F64()

    fun startMonitor(cameraParametersFile: File) {
        tmpScope = CoroutineScope(SupervisorJob() + dispatcher)

        val calibration = CalibrationIO.load<MonoPlaneParameters>(cameraParametersFile)

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
    }

    fun stopMonitor() {
        tmpScope?.cancel()
        tmpScope = null
        relatedPosition.zero()
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
}