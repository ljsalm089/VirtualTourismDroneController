package dji.sampleV5.aircraft.models

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.opencsv.bean.CsvBindByName
import com.opencsv.bean.CsvToBeanBuilder
import com.opencsv.bean.StatefulBeanToCsvBuilder
import dji.sdk.keyvalue.key.BatteryKey.KeyBatteryTemperature
import dji.sdk.keyvalue.key.BatteryKey.KeyBatteryTemperatureException
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey.KeyAircraftAttitude
import dji.sdk.keyvalue.key.FlightControllerKey.KeyAircraftLocation3D
import dji.sdk.keyvalue.key.FlightControllerKey.KeyAircraftVelocity
import dji.sdk.keyvalue.key.FlightControllerKey.KeyCompassHasError
import dji.sdk.keyvalue.key.FlightControllerKey.KeyCompassHeading
import dji.sdk.keyvalue.key.FlightControllerKey.KeyCompassState
import dji.sdk.keyvalue.key.FlightControllerKey.KeyConnection
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSInterferenceState
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSIsValid
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSModeFailureReason
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSSatelliteCount
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSSignalLevel
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIMUCalibrationInfo
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIMUCount
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIsFlying
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIsNearDistanceLimit
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIsNearHeightLimit
import dji.sdk.keyvalue.key.FlightControllerKey.KeyUltrasonicHasError
import dji.sdk.keyvalue.key.FlightControllerKey.KeyUltrasonicHeight
import dji.sdk.keyvalue.key.FlightControllerKey.KeyWindDirection
import dji.sdk.keyvalue.key.FlightControllerKey.KeyWindSpeed
import dji.sdk.keyvalue.key.FlightControllerKey.KeyWindWarning
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.simulator.InitializationSettings
import dji.v5.manager.aircraft.simulator.SimulatorManager
import dji.v5.manager.aircraft.simulator.SimulatorState
import dji.v5.manager.aircraft.simulator.SimulatorStatusListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.internal.closeQuietly
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class GeodeticLocation(
    @CsvBindByName(column = "GeodeticCode") var geodeticCode: String? = null,
    @CsvBindByName(column = "Name") var name: String? = null,
    @CsvBindByName(column = "NodeId") var nodeId: String? = null,
    @CsvBindByName(column = "LandDistrict") var district: String? = null,
    @CsvBindByName(column = "MarkDescription") var desc: String? = null,
    @CsvBindByName(column = "MaintDate") var lastMaintDate: String? = null,
    @CsvBindByName(column = "Lat", required = true) var latitude: Double = 0.0,
    @CsvBindByName(column = "Lon", required = true) var longitude: Double = 0.0,
    @CsvBindByName(column = "CalcDate") var calcDate: String? = null,
    @CsvBindByName(column = "OrthHeight", required = true) var height: Float = 0f,
)

data class LocationRecord(
    @CsvBindByName(column = "BenchmarkLatitude") var bLatitude: Double,
    @CsvBindByName(column = "BenchmarkLongitude") var bLongitude: Double,
    @CsvBindByName(column = "BenchmarkAltitude") var bAltitude: Float,
    @CsvBindByName(column = "DroneLatitude") var dLatitude: Double,
    @CsvBindByName(column = "DroneLongitude") var dLongitude: Double,
    @CsvBindByName(column = "DroneAltitude") var dAltitude: Float,
    @CsvBindByName(column = "UpdateTimestamp") var timestamp: Long,
    @CsvBindByName(column = "BenchmarkId") var benchmarkId: String,
    @CsvBindByName(column = "Offset") var offsetToBenchmark: Float,  // read distance of the offset
    @CsvBindByName(column = "OffsetAroundLatitude") var offsetAroundLatitude: Float,  // the distance of the offset around the latitude (same latitude but change the longitude)
    @CsvBindByName(column = "OffsetAroundLongitude") var offsetAroundLongitude: Float,  // the distance of the offset around the longitude (same longitude but change the latitude)
    @CsvBindByName(column = "SatelliteCount") var satelliteCount: Int
)

data class CurrentRecordInfo(
    val locationRecords: MutableList<LocationRecord>,
    val benchMark: GeodeticLocation,
    val fileName: String,
)

data class Location(
    var latitude: Double?,
    var longitude: Double?,
    var height: Float?,
)

fun GeodeticLocation.toLocation(): Location {
    return Location(latitude, longitude, height)
}

fun LocationCoordinate3D.toLocation(): Location {
    return Location(latitude, longitude, altitude.toFloat())
}

class GPSMeasurementVM : ViewModel(), SimulatorStatusListener {

    val geodeticPosList: MutableLiveData<List<GeodeticLocation>> = MutableLiveData()

    val trackingType: MutableLiveData<List<String>> = MutableLiveData()

    val mapLocation: MutableLiveData<Location> = MutableLiveData()

    val droneLocation: MutableLiveData<Location> = MutableLiveData()

    val gapDistance: MutableLiveData<Pair<Float?, Float?>> = MutableLiveData()

    val recordStatus: MutableLiveData<Boolean> = MutableLiveData()

    val recordFilePath: MutableLiveData<String?> = MutableLiveData()

    val droneStatus = MutableLiveData(HashMap<String, String>())

    val simulationStatus = MutableLiveData<Boolean>(false)

    private lateinit var apiClient: FusedLocationProviderClient

    private var currentRecordInfo: CurrentRecordInfo? = null

    private var currentTrackingType: String? = null

    private lateinit var context: Context

    @SuppressLint("MissingPermission")
    private fun changeToCurrentLocation() {
        apiClient.lastLocation.addOnSuccessListener {
            mapLocation.postValue(Location(it.latitude, it.longitude, it.altitude.toFloat()))
        }.addOnFailureListener {
            mapLocation.postValue(Location(-45.88880, 170.55173, null))
        }
    }

    private fun formatInfo(any: Any?): String {
        return any?.toString() ?: "--"
    }

    private fun <T> DJIKeyInfo<T>.listen(
        hashKey: String,
        logPrefix: String,
        converter: (T?) -> String,
    ) {
        this.create().listen(this@GPSMeasurementVM) { t ->
            val info = converter.invoke(t)

            Timber.d("$logPrefix: $info")

            droneStatus.value?.set(hashKey, info)
            droneStatus.postValue(droneStatus.value)
        }
    }

    private fun initObserveEvents() {
        KeyConnection.listen("Flight control connection", "Flight control is connected") {
            "$it"
        }
        KeyIsFlying.listen("Drone is flying", "Drone is flying") {
            "$it"
        }
        KeyAircraftAttitude.listen("Drone attitude (Y/R/P)", "Update drone's attitude (Y/R/P)") {
            "${formatInfo(it?.yaw)} / ${formatInfo(it?.roll)} / ${formatInfo(it?.pitch)}"
        }
        KeyAircraftVelocity.listen("Drone velocity (X/Y/Z)", "Update drone's velocity (X/Y/Z)") {
            "${formatInfo(it?.x)} / ${formatInfo(it?.y)} / ${formatInfo(it?.z)}"
        }
        KeyGPSIsValid.listen("Is GPS valid", "if gps is valid on the drone") {
            formatInfo(it)
        }
        KeyGPSInterferenceState.listen("GPS interference state", "drone gps interference state") {
            formatInfo(it?.name)
        }
        KeyGPSSignalLevel.listen("Drone GPS signal level", "drone gps signal level") {
            formatInfo(it?.name)
        }
        KeyGPSSatelliteCount.listen("GPS satellite count", "current gps satellite count") {
            "$it"
        }
        KeyGPSModeFailureReason.listen("GPS fail reason", "GPS mode failure reason") {
            formatInfo(it?.name)
        }
        KeyCompassHeading.listen("Compass Heading", "Current compass heading") {
            it.toString()
        }
        KeyCompassState.listen("Compass state", "Compass state update") {
            val stringBuilder = StringBuilder()

            it?.apply {
                for (tmpState in this) {
                    stringBuilder.append("${tmpState.compassSensorState},${tmpState.compassSensorValue}")
                        .append(" / ")
                }
            }

            stringBuilder.toString()
        }
        KeyCompassHasError.listen("Compass error", "if compass has error") {
            it.toString()
        }
        KeyUltrasonicHasError.listen("Ultrasonic error", "If ultrasonic has error") {
            it.toString()
        }
        KeyUltrasonicHeight.listen("Ultrasonic height", "current height from ultrasonic:") {
            it.toString()
        }
        KeyWindWarning.listen("Wind warning", "Received wind warning") {
            formatInfo(it?.name)
        }
        KeyWindSpeed.listen("Wind speed (dm/s)", "Current wind speed (dm/s)") {
            it.toString()
        }
        KeyWindDirection.listen("Wind direction", "Current wind direction") {
            formatInfo(it?.name)
        }
        KeyIMUCount.listen("IMU count", "IMU count") {
            it.toString()
        }
        KeyIMUCalibrationInfo.listen("IMU calibration", "IMU calibration information") {
            formatInfo(it.toString())
        }
        KeyIsNearDistanceLimit.listen("Near distance limit", "Is current near distance limit") {
            it.toString()
        }
        KeyIsNearHeightLimit.listen("Near height limit", "Is current near height limit") {
            it.toString()
        }
        KeyBatteryTemperature.listen("Battery temperature", "Current battery temperature") {
            it.toString()
        }
        KeyBatteryTemperatureException.listen(
            "Battery temp. exception",
            "battery temperature exception"
        ) {
            formatInfo(it?.toJson()?.toString())
        }
        SimulatorManager.getInstance().addSimulatorStateListener(this)

        KeyAircraftLocation3D.create().listen(this) { loc ->
            loc?.let {
                Timber.d("Received location data from drone: $loc")
                droneLocation.postValue(loc.toLocation())

                // calculate gap distance between two locations.
                mapLocation.value?.let { mapLoc ->
                    val results = FloatArray(3)
                    android.location.Location.distanceBetween(
                        loc.latitude, loc.longitude,
                        mapLocation.value!!.latitude!!, mapLocation.value!!.longitude!!, results
                    )
                    val offset = results[0]

                    android.location.Location.distanceBetween(loc.latitude, mapLocation.value!!.longitude!!,
                        mapLocation.value!!.latitude!!, mapLocation.value!!.longitude!!, results
                    )
                    val offsetAroundLatitude = results[0]

                    android.location.Location.distanceBetween(mapLocation.value!!.latitude!!, loc.longitude,
                        mapLocation.value!!.latitude!!, mapLocation.value!!.longitude!!, results
                    )
                    val offsetAroundLongitude = results[0]

                    gapDistance.postValue(results[0] to loc.altitude.toFloat() - mapLoc.height!!)

                    val satelliteCount = droneStatus.value?.get("GPS satellite count")?.toInt() ?: 0

                    currentRecordInfo?.let {
                        val newRecord = LocationRecord(
                            it.benchMark.latitude,
                            it.benchMark.longitude,
                            it.benchMark.height,
                            loc.latitude,
                            loc.longitude,
                            loc.altitude.toFloat(),
                            System.currentTimeMillis(),
                            it.benchMark.nodeId!!,
                            offset,
                            offsetAroundLatitude,
                            offsetAroundLongitude,
                            satelliteCount
                        )
                        it.locationRecords.add(newRecord)
                    }
                }
            } ?: Timber.d("callback for aircraft location but the value is invalid")
        }
    }

    fun initialize(context: Context) {
        this.context = context
        apiClient = LocationServices.getFusedLocationProviderClient(context)

        geodeticPosList.value = listOf()
        gapDistance.value = Pair(0f, 0f)
        droneLocation.value = Location(null, null, null)
        recordStatus.value = false
        recordFilePath.value = null
        trackingType.postValue(listOf(
            "Tracking Fixed Point",
            "Tracking Linear Path",
            "Tracking Flying Randomly"
        ))

        viewModelScope.launch(Dispatchers.IO) {
            val fs = context.assets.open("geodetic_db.csv")

            val geoPositionList = CsvToBeanBuilder<GeodeticLocation>(InputStreamReader(fs))
                .withIgnoreLeadingWhiteSpace(true)
                .withType(GeodeticLocation::class.java).build().parse()

            fs.close()

            viewModelScope.launch(Dispatchers.Main) { geodeticPosList.postValue(geoPositionList) }
        }

        initObserveEvents()
    }

    fun selectLocation(location: GeodeticLocation?) {
        val index = location?.let {
            geodeticPosList.value?.indexOf(location) ?: -1
        } ?: -1

        if (index < 0) {
            if (this::apiClient.isInitialized) {
                changeToCurrentLocation()
            }
            return
        }

        mapLocation.postValue(location!!.toLocation())

        // mark the selected item.
    }

    fun selectTrackingType(type: String?) {
        this.currentTrackingType = type
    }

    fun startOrStopRecord() {
        if (recordStatus.value == true) {
            // is currently recording, need to stop

            if (null == currentRecordInfo) { // ui status mismatch, correct it
                recordStatus.postValue(false)
                return
            }

            // stop and log the data into file
            val recordInfo = currentRecordInfo!!
            currentRecordInfo = null
            recordStatus.postValue(false)
            recordFilePath.postValue(null)

            viewModelScope.launch(Dispatchers.IO) {
                val dir = File(context.getExternalFilesDir(null), "GPSRecords")
                if (!dir.exists()) {
                    dir.mkdirs()
                }

                val targetFile = File(dir, recordInfo.fileName)
                val writer = FileWriter(targetFile)
                val csvWriter = StatefulBeanToCsvBuilder<LocationRecord>(writer).build();
                csvWriter.write(recordInfo.locationRecords)
                writer.closeQuietly()
            }
        } else {
            // not recording, need to start

            if (null != currentRecordInfo) { // ui status mismatch, correct it
                recordStatus.postValue(true)
                recordFilePath.postValue(currentRecordInfo!!.fileName)
                return
            }

            // start a new record
            var benchMark: GeodeticLocation? = null
            for (tmp in geodeticPosList.value!!) {
                if (tmp.latitude == mapLocation.value!!.latitude && tmp.longitude == mapLocation.value!!.longitude) {
                    benchMark = tmp;
                    break
                }
            }
            if (null == benchMark) {
                Timber.e("can not find a benchmark point from list")
                return
            }

            val trackingTypeString = currentTrackingType?.replace(" ", "_") ?: "None"
            currentRecordInfo = CurrentRecordInfo(
                ArrayList(),
                benchMark,
                "${trackingTypeString}_${benchMark.nodeId}_${
                    SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault()).format(
                        Date()
                    )
                }.csv"
            )
            recordFilePath.postValue(currentRecordInfo?.fileName)
            recordStatus.postValue(true)
        }
    }

    fun clickMapMarker(location: GeodeticLocation): Boolean {
        selectLocation(location)
        return true
    }

    fun clickSimulation() {
        if (!simulationStatus.value!!) {
            val initializedLocation =
                LocationCoordinate2D(mapLocation.value!!.latitude, mapLocation.value!!.longitude)
            val settings = InitializationSettings(initializedLocation, 10)
            SimulatorManager.getInstance()
                .enableSimulator(settings, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Timber.i("Start the drone simulator successfully")
                        simulationStatus.postValue(true)
                    }

                    override fun onFailure(p0: IDJIError) {
                        Timber.e("Failed to start the simulator: $p0")
                        simulationStatus.postValue(false)
                    }

                })
        } else {
            SimulatorManager.getInstance()
                .disableSimulator(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Timber.i("Stop the drone simulator successfully")
                        simulationStatus.postValue(false)
                    }

                    override fun onFailure(p0: IDJIError) {
                        Timber.e("Failed to stop the simulator: $p0")
                        simulationStatus.postValue(true)
                    }

                })
        }
    }

    fun destroy() {
        KeyManager.getInstance().cancelListen(this)
        SimulatorManager.getInstance().removeSimulatorStateListener(this)
    }

    override fun onUpdate(state: SimulatorState) {
        val simulatorState =
            "MotorOn-${state.areMotorsOn()} / Flying-${state.isFlying} / YRP ${state.yaw}-${state.roll}-${state.pitch} / Position(XYZ) ${state.positionX}-${state.positionY}-${state.positionZ}"
        Timber.i("current simulator state: $simulatorState")
        droneStatus.value?.set("Simulator state", simulatorState)
    }
}