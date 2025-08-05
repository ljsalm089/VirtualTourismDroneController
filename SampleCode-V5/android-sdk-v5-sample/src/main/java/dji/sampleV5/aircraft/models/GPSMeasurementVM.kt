package dji.sampleV5.aircraft.models

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.opencsv.bean.CsvBindByName
import com.opencsv.bean.CsvToBeanBuilder
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey.KeyConnection
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIsFlying
import dji.sdk.keyvalue.key.FlightControllerKey.KeyAircraftAttitude
import dji.sdk.keyvalue.key.FlightControllerKey.KeyAircraftVelocity
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSIsValid
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSInterferenceState
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSSignalLevel
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSSatelliteCount
import dji.sdk.keyvalue.key.FlightControllerKey.KeyGPSModeFailureReason
import dji.sdk.keyvalue.key.FlightControllerKey.KeyAircraftLocation3D
import dji.sdk.keyvalue.key.FlightControllerKey.KeyCompassHeading
import dji.sdk.keyvalue.key.FlightControllerKey.KeyCompassState
import dji.sdk.keyvalue.key.FlightControllerKey.KeyCompassHasError
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIMUCount
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIMUCalibrationInfo
import dji.sdk.keyvalue.key.FlightControllerKey.KeyUltrasonicHeight
import dji.sdk.keyvalue.key.FlightControllerKey.KeyUltrasonicHasError
import dji.sdk.keyvalue.key.FlightControllerKey.KeyWindWarning
import dji.sdk.keyvalue.key.FlightControllerKey.KeyWindSpeed
import dji.sdk.keyvalue.key.FlightControllerKey.KeyWindDirection
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIsNearDistanceLimit
import dji.sdk.keyvalue.key.FlightControllerKey.KeyIsNearHeightLimit
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "GPSMeasurementVM"

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

class GPSMeasurementVM : ViewModel() {

    val geodeticPosList: MutableLiveData<List<GeodeticLocation>> = MutableLiveData()

    val mapLocation: MutableLiveData<Location> = MutableLiveData()

    val droneLocation: MutableLiveData<Location> = MutableLiveData()

    val gapDistance: MutableLiveData<Pair<Float?, Float?>> = MutableLiveData()

    val recordStatus: MutableLiveData<Boolean> = MutableLiveData()

    val recordFilePath: MutableLiveData<String?> = MutableLiveData()

    val droneStatus = MutableLiveData(HashMap<String, String>())

    private lateinit var apiClient: FusedLocationProviderClient

    private var currentRecordInfo: CurrentRecordInfo? = null

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

    private fun <T> DJIKeyInfo<T>.listen(hashKey: String, logPrefix: String, converter: (T?) -> String) {
        this.create().listen(this@GPSMeasurementVM) { t->
            val info = converter.invoke(t)

            Log.d(TAG, "$logPrefix: $info")

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
                    stringBuilder.append("${tmpState.compassSensorState},${tmpState.compassSensorValue}").append(" / ")
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

        KeyAircraftLocation3D.create().listen(this) { loc ->
            loc?.let {
                Log.d(TAG, "Received location data from drone: $loc")
                droneLocation.postValue(loc.toLocation())

                // calculate gap distance between two locations.
                mapLocation.value?.let { mapLoc ->
                    val results = FloatArray(3)
                    android.location.Location.distanceBetween(
                        loc.latitude, loc.longitude,
                        mapLocation.value!!.latitude!!, mapLocation.value!!.longitude!!, results
                    )

                    gapDistance.postValue(results[0] to loc.altitude.toFloat() - mapLoc.height!!)

                    currentRecordInfo?.let {
                        val newRecord = LocationRecord(
                            it.benchMark.latitude,
                            it.benchMark.longitude,
                            it.benchMark.height,
                            loc.latitude,
                            loc.longitude,
                            loc.altitude.toFloat(),
                            System.currentTimeMillis(),
                            it.benchMark.nodeId!!
                        )
                        it.locationRecords.add(newRecord)
                    }
                }
            } ?: Log.d(TAG, "callback for aircraft location but the value is invalid")
        }

    }

    fun initialize(context: Context) {
        apiClient = LocationServices.getFusedLocationProviderClient(context)

        geodeticPosList.value = listOf()
        gapDistance.value = Pair(null, null)
        droneLocation.value = Location(null, null, null)
        recordStatus.value = false
        recordFilePath.value = null

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
            changeToCurrentLocation()
            return
        }

        mapLocation.postValue(location!!.toLocation())

        // mark the selected item.
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

            viewModelScope.launch (Dispatchers.Main) {

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
                Log.e(TAG, "can not find a benchmark point from list")
                return
            }
            currentRecordInfo = CurrentRecordInfo(
                ArrayList(),
                benchMark,
                "${benchMark.nodeId}_${
                    SimpleDateFormat("yyyy_mm_dd_HH_MM_ss", Locale.getDefault()).format(
                        Date()
                    )
                }"
            )
            recordFilePath.postValue(currentRecordInfo?.fileName)
            recordStatus.postValue(true)
        }
    }

    fun clickMapMarker(location: GeodeticLocation): Boolean {
        selectLocation(location)
        return true
    }

    fun destroy() {
        KeyManager.getInstance().cancelListen(this)
    }
}