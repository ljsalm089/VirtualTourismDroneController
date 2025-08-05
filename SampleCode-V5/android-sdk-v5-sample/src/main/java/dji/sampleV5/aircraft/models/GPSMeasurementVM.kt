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
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.et.listen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    val droneStatus: MutableLiveData<Map<String, String>> = MutableLiveData()

    private lateinit var apiClient: FusedLocationProviderClient

    private var djiLocationKey: DJIKey<LocationCoordinate3D>? = null

    private var djiConnectionKey: DJIKey<Boolean>? = null

    private var currentRecordInfo: CurrentRecordInfo? = null

    private var job: Job? = null

    @SuppressLint("MissingPermission")
    private fun changeToCurrentLocation() {
        apiClient.lastLocation.addOnSuccessListener {
            mapLocation.postValue(Location(it.latitude, it.longitude, it.altitude.toFloat()))
        }.addOnFailureListener {
            mapLocation.postValue(Location(-45.88880, 170.55173, null))
        }
    }

    fun initialize(context: Context) {
        apiClient = LocationServices.getFusedLocationProviderClient(context)

        geodeticPosList.value = listOf()
        viewModelScope.launch(Dispatchers.IO) {
            val fs = context.assets.open("geodetic_db.csv")

            val geoPositionList = CsvToBeanBuilder<GeodeticLocation>(InputStreamReader(fs))
                .withIgnoreLeadingWhiteSpace(true)
                .withType(GeodeticLocation::class.java).build().parse()

            fs.close()

            viewModelScope.launch(Dispatchers.Main) { geodeticPosList.postValue(geoPositionList) }
        }

        djiConnectionKey = FlightControllerKey.KeyConnection.create()
        djiConnectionKey?.listen(this@GPSMeasurementVM, true) { isConnected ->
            Log.d(TAG, "Flight control is connected: $isConnected")

            if (true != isConnected) return@listen

            djiConnectionKey?.cancelListen(this@GPSMeasurementVM)

            job?.cancel()

            job = viewModelScope.launch (Dispatchers.IO) {
                while (isActive) {
                    val location = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D).get()
                    if (null == location) {
                        Log.e(TAG, "Unable to get location of drone")
                    } else {
                        val loc = location

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
                    }
                    delay(1000)
                }
            }
            djiLocationKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D)
            djiLocationKey?.listen(this, false) { loc ->
                Log.d(TAG, "Obtain the location from the drone.")
            }
        }

        gapDistance.value = Pair(null, null)
        droneLocation.value = Location(null, null, null)

        recordStatus.value = false
        recordFilePath.value = null
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
        djiLocationKey?.cancelListen(this)
        djiConnectionKey?.cancelListen(this)
    }
}