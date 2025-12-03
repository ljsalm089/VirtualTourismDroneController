package dji.sampleV5.aircraft.models

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.opencsv.bean.CsvBindByName
import com.opencsv.bean.CsvToBeanBuilder
import com.opencsv.bean.StatefulBeanToCsvBuilder
import dji.sampleV5.aircraft.DJIApplication.Companion.idToString
import dji.sampleV5.aircraft.R
import dji.sampleV5.aircraft.utils.LogLevel
import dji.sampleV5.aircraft.virtualcontroller.DroneStatusMonitor
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.et.listen
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
    @CsvBindByName(column = "SatelliteCount") var satelliteCount: Int,
    @CsvBindByName(column = "LogMark") var mark: String = ""
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

    val logMarker: MutableLiveData<List<String>> = MutableLiveData()

    val trackingType: MutableLiveData<List<String>> = MutableLiveData()

    val mapLocation: MutableLiveData<Location> = MutableLiveData()

    val droneLocation: MutableLiveData<String> = MutableLiveData()

    val gapDistance: MutableLiveData<Pair<Float?, Float?>> = MutableLiveData()

    val recordStatus: MutableLiveData<Boolean> = MutableLiveData()

    val recordFilePath: MutableLiveData<String?> = MutableLiveData()

    val droneStatus = MutableLiveData(HashMap<String, String>())

    val simulationStatus = MutableLiveData<Boolean>(false)

    private lateinit var apiClient: FusedLocationProviderClient

    private var currentRecordInfo: CurrentRecordInfo? = null

    private var currentTrackingType: String? = null

    private var markType: String = ""

    private lateinit var droneMonitor: DroneStatusMonitor

    private val locationKey = FlightControllerKey.KeyAircraftLocation3D
    private var locationObserver: ((DJIKeyInfo<*>, Any?) -> Unit)? = null

    private val velocityKey = FlightControllerKey.KeyAircraftVelocity
    private var velocityObserver: ((DJIKeyInfo<*>, Any?) -> Unit)? = null

    private var velocitySampleTimestamp: Long = 0


    private lateinit var context: Context

    @SuppressLint("MissingPermission")
    private fun changeToCurrentLocation() {
        apiClient.lastLocation.addOnSuccessListener {
            mapLocation.postValue(Location(it.latitude, it.longitude, it.altitude.toFloat()))
        }.addOnFailureListener {
            mapLocation.postValue(Location(-45.88880, 170.55173, null))
        }
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

    private fun handleDroneLocation(loc: LocationCoordinate3D) {
        Timber.d("Received location data from drone: $loc")

        // calculate gap distance between two locations.
        mapLocation.value?.let { mapLoc ->
            val results = FloatArray(3)
            android.location.Location.distanceBetween(
                loc.latitude, loc.longitude,
                mapLocation.value!!.latitude!!, mapLocation.value!!.longitude!!, results
            )
            val offset = results[0]

            android.location.Location.distanceBetween(
                loc.latitude, mapLocation.value!!.longitude!!,
                mapLocation.value!!.latitude!!, mapLocation.value!!.longitude!!, results
            )
            val offsetAroundLatitude = results[0]

            android.location.Location.distanceBetween(
                mapLocation.value!!.latitude!!, loc.longitude,
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
                    satelliteCount,
                    markType
                )
                it.locationRecords.add(newRecord)
            }
        }
    }

    fun initialize(context: Context) {
        this.context = context
        apiClient = LocationServices.getFusedLocationProviderClient(context)

        geodeticPosList.value = listOf()
        gapDistance.value = Pair(0f, 0f)
        droneLocation.value = "- / - / -"
        recordStatus.value = false
        recordFilePath.value = null
        trackingType.postValue(listOf(
            "GPS",
            "Velocity"
        ))
        logMarker.postValue(listOf(
            "GPS Fixed Point Origin",
            "GPS Fixed Point North",
            "GPS Fixed Point East",
            "GPS Fixed Point South",
            "GPS Fixed Point West",
            "GPS Fixed Point Cross",

            "GPS Linear Position Change -- Forward",
            "GPS Linear Position Change -- Right",

            "-----",
            "Velocity Go Forward",
            "Velocity Go Right",
            "Velocity Go Back",
            "Velocity Go Left"
        ))

        viewModelScope.launch(Dispatchers.IO) {
            val fs = context.assets.open("geodetic_db.csv")

            val geoPositionList = CsvToBeanBuilder<GeodeticLocation>(InputStreamReader(fs))
                .withIgnoreLeadingWhiteSpace(true)
                .withType(GeodeticLocation::class.java).build().parse()

            fs.close()

            viewModelScope.launch(Dispatchers.Main) { geodeticPosList.postValue(geoPositionList) }
        }

        val droneLocationKey = R.string.hint_drone_current_position.idToString()

        droneMonitor = DroneStatusMonitor(viewModelScope, {level, msg, e ->
            // do nothing but logging them
            Timber.log(level, msg, e)
        }) { keyAndValue ->
            // filter out desired

            if (keyAndValue.containsKey(droneLocationKey)) {
                droneLocation.postValue(keyAndValue[droneLocationKey])
            }
            updateStatus(keyAndValue, droneLocationKey)
        }

        locationObserver = droneMonitor.register(locationKey) { key, value ->
            if (locationKey.innerIdentifier == key.innerIdentifier && null != (value as? LocationCoordinate3D)) {
                handleDroneLocation(value)
            }
        }

        droneMonitor.startMonitoring()
    }

    private fun updateStatus(
        keyAndValue: Map<String, String>,
        ignoreKey: String = ""
    ) {
        for (tmp in keyAndValue.entries) {
            if (ignoreKey == tmp.key) continue

            droneStatus.value?.set(tmp.key, tmp.value)
        }
        droneStatus.postValue(droneStatus.value)
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

    fun markOnLog(isMarking: Boolean, content: String) {
        if (isMarking) {
            markType = content
        } else {
            markType = ""
        }

        val markLog = if (isMarking) {
            // stop mark
            "$content ----> Stop"
        } else {
            // start mark
            "$content ----> Start"
        }
        if ("GPS" != currentTrackingType) {
            Timber.log(LogLevel.VERBOSE_DRONE_POSITION_CHANGE_BASED_ON_VELOCITY, markLog)
        }
    }

    fun startOrStopRecord() {
        if (recordStatus.value == true) {
            // is currently recording, need to stop
            if ("GPS" == currentTrackingType) {
                stopRecordGPS()
            } else {
                stopTrackVelocity()
            }
        } else {
            // not recording, need to start
            if ("GPS" == currentTrackingType) {
                // start tracking GPS changes
                startRecordGPS()
            } else {
                // start tracking Velocity changes
                startTrackVelocity()
            }
        }
    }

    private var northPosInstant = 0.0
    private var eastPosInstant = 0.0


    private var northPosAverage = 0.0
    private var eastPosAverage = 0.0
    private var lastNorthV = 0.0
    private var lastEastV = 0.0

    private fun startTrackVelocity() {
        if (recordStatus.value == true) {
            return
        }

        velocitySampleTimestamp = SystemClock.elapsedRealtime()
        northPosAverage = 0.0
        eastPosAverage = 0.0
        northPosInstant = 0.0
        eastPosInstant = 0.0
        lastEastV = 0.0
        lastNorthV = 0.0

        velocityObserver = droneMonitor.register(velocityKey) { key, value ->
            if (velocityKey.innerIdentifier == key.innerIdentifier && null != (value as? Velocity3D)) {
                val currentSampleTimestamp = SystemClock.elapsedRealtime()
                val timeGap = (currentSampleTimestamp - velocitySampleTimestamp) / 1000.0

                northPosInstant += value.x * timeGap
                eastPosInstant += value.y * timeGap

                northPosAverage += (value.x + lastNorthV) * timeGap / 2
                eastPosAverage += (value.y + lastEastV) * timeGap / 2

                lastNorthV = value.x
                lastEastV = value.y

                Timber.log(LogLevel.VERBOSE_DRONE_POSITION_CHANGE_BASED_ON_VELOCITY, "instant position: ($northPosInstant, $eastPosInstant), \t average position: ($northPosAverage, $eastPosAverage)")
                velocitySampleTimestamp = currentSampleTimestamp

                updateStatus(mapOf(
                    "Instant Position" to "$northPosInstant / $eastPosInstant",
                    "Average Position" to "$northPosAverage / $eastPosAverage"
                ))
            }
        }

        recordStatus.postValue(true)
    }

    private fun stopTrackVelocity() {
        if (recordStatus.value == false) {
            return
        }

        velocityObserver?.let {
            droneMonitor.unregister(velocityKey, it)
        }

        recordStatus.postValue(false)
    }

    private fun stopRecordGPS() {
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
    }

    private fun startRecordGPS() {
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
        locationObserver?.let {
            droneMonitor.unregister(locationKey, it)
        }
        droneMonitor.stopMonitoring()
        SimulatorManager.getInstance().removeSimulatorStateListener(this)
    }

    override fun onUpdate(state: SimulatorState) {
        val simulatorState =
            "MotorOn-${state.areMotorsOn()} / Flying-${state.isFlying} / YRP ${state.yaw}-${state.roll}-${state.pitch} / Position(XYZ) ${state.positionX}-${state.positionY}-${state.positionZ}"
        Timber.i("current simulator state: $simulatorState")
        droneStatus.value?.set("Simulator state", simulatorState)
    }
}