package dji.sampleV5.aircraft

import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import dji.sampleV5.aircraft.databinding.ActivityGpsMeasurementBinding
import dji.sampleV5.aircraft.models.GPSMeasurementVM

class GPSMeasurementActivity : AppCompatActivity() {


    private lateinit var binding: ActivityGpsMeasurementBinding

    private val viewModel: GPSMeasurementVM by viewModels()

    private lateinit var map: GoogleMap

    private fun initializeViewModel() {
        viewModel.initialize(this)

        viewModel.geodeticPosList.observe(this) {

        }

        viewModel.mapLocation.observe(this) {
            // update map location
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder().zoom(17.5f).target(LatLng(it.first, it.second)).build()
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?, persistentState: PersistableBundle?) {
        super.onCreate(savedInstanceState, persistentState)

        binding = ActivityGpsMeasurementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.vMap.onCreate(savedInstanceState)
        binding.vMap.getMapAsync {
            map = it
            initializeViewModel()
        }
    }

    override fun onStart() {
        super.onStart()
        binding.vMap.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.vMap.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.vMap.onPause()
    }


    override fun onStop() {
        super.onStop()
        binding.vMap.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.vMap.onDestroy()

        viewModel.destroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.vMap.onSaveInstanceState(outState)
    }


    override fun onLowMemory() {
        super.onLowMemory()
        binding.vMap.onLowMemory()
    }
}