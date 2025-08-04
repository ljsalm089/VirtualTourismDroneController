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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStreamReader

data class GeodeticLocation (
    @CsvBindByName(column = "GeodeticCode") var geodeticCode: String?,
    @CsvBindByName(column = "Name") var name: String?,
    @CsvBindByName(column = "NodeId") var nodeId: String?,
    @CsvBindByName(column = "LandDistrict") var district: String?,
    @CsvBindByName(column = "MarkDescription") var desc: String?,
    @CsvBindByName(column = "MaintDate") var lastMaintDate: String?,
    @CsvBindByName(column = "Lat", required = true) var latitude: Double,
    @CsvBindByName(column = "Lon", required = true) var longitude: Double,
    @CsvBindByName(column = "CalcDate") var calcDate: String?,
    @CsvBindByName(column = "OrthHeight", required = true) var height: Float
)

class GPSMeasurementVM : ViewModel() {

    val geodeticPosList: MutableLiveData<List<GeodeticLocation>> = MutableLiveData()

    val mapLocation: MutableLiveData<Pair<Double, Double>> = MutableLiveData()

    private lateinit var apiClient: FusedLocationProviderClient

    @SuppressLint("MissingPermission")
    fun initialize(context: Context) {
        apiClient = LocationServices.getFusedLocationProviderClient(context)

        geodeticPosList.value = listOf()
        viewModelScope.launch (Dispatchers.IO) {
            val fs = context.assets.open("geodetic_db.csv")

            val geoPositionList = CsvToBeanBuilder<GeodeticLocation>(InputStreamReader(fs)).build().parse()

            fs.close()

            viewModelScope.launch (Dispatchers.Main) { geodeticPosList.postValue(geoPositionList) }
        }


        apiClient.lastLocation.addOnSuccessListener {
            mapLocation.postValue(it.latitude to it.longitude)
        }.addOnFailureListener {
            mapLocation.postValue(-45.88880 to 170.55173)
        }
    }

    fun destroy() {
    }
}