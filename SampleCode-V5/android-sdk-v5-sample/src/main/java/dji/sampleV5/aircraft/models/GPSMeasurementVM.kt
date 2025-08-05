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
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.et.cancelListen
import dji.v5.et.create
import dji.v5.et.listen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStreamReader

data class GeodeticLocation (
    @CsvBindByName(column = "GeodeticCode") var geodeticCode: String? = null,
    @CsvBindByName(column = "Name") var name: String? = null,
    @CsvBindByName(column = "NodeId") var nodeId: String? = null,
    @CsvBindByName(column = "LandDistrict") var district: String? = null,
    @CsvBindByName(column = "MarkDescription") var desc: String? = null,
    @CsvBindByName(column = "MaintDate") var lastMaintDate: String? = null,
    @CsvBindByName(column = "Lat", required = true) var latitude: Double = 0.0,
    @CsvBindByName(column = "Lon", required = true) var longitude: Double = 0.0,
    @CsvBindByName(column = "CalcDate") var calcDate: String? = null,
    @CsvBindByName(column = "OrthHeight", required = true) var height: Float = 0f
)

data class Location (
    var latitude: Double?,
    var longitude: Double?,
    var height: Float?
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

    private lateinit var apiClient: FusedLocationProviderClient

    private var djiLocationKey: DJIKey<LocationCoordinate3D>? = null

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
        viewModelScope.launch (Dispatchers.IO) {
            val fs = context.assets.open("geodetic_db.csv")

            val geoPositionList = CsvToBeanBuilder<GeodeticLocation>(InputStreamReader(fs))
                .withIgnoreLeadingWhiteSpace(true)
                .withType(GeodeticLocation::class.java).build().parse()

            fs.close()

            viewModelScope.launch (Dispatchers.Main) { geodeticPosList.postValue(geoPositionList) }
        }

        djiLocationKey = FlightControllerKey.KeyAircraftLocation3D.create()
        djiLocationKey?.listen(this, false) { loc ->
            droneLocation.postValue(loc!!.toLocation())

            // calculate gap distance between two locations.
            mapLocation.value?.let { mapLoc ->
                val results = FloatArray(3)
                android.location.Location.distanceBetween(loc.latitude, loc.longitude,
                    mapLocation.value!!.latitude!!, mapLocation.value!!.longitude!!, results)

                gapDistance.postValue(results[0] to loc.altitude.toFloat() - mapLoc.height!!)
            }
        }

        gapDistance.value = Pair(null, null)
        droneLocation.value = Location(null, null, null)
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

    fun clickMapMarker(location: GeodeticLocation): Boolean {
        selectLocation(location)
        return true
    }

    fun destroy() {
        djiLocationKey?.cancelListen(this)
    }
}