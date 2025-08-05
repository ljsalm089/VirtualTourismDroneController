package dji.sampleV5.aircraft

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import dji.sampleV5.aircraft.databinding.ActivityGpsMeasurementBinding
import dji.sampleV5.aircraft.models.GPSMeasurementVM
import dji.sampleV5.aircraft.models.GeodeticLocation

class GPSMeasurementActivity : AppCompatActivity() {


    private lateinit var binding: ActivityGpsMeasurementBinding

    private val viewModel: GPSMeasurementVM by viewModels()

    private lateinit var map: GoogleMap

    private fun formatLocation(any: Any?): String {
        return any?.toString() ?: "--"
    }

    private fun initializeViewModel() {
        map.uiSettings.let {
            it.isMyLocationButtonEnabled = true
            it.setAllGesturesEnabled(true)
        }
        viewModel.initialize(this)

        viewModel.geodeticPosList.observe(this) {
            if (binding.vMap.tag is List<*>) {
                val markers = binding.vMap.tag as List<Marker>
                markers.forEach { marker ->
                    marker.remove()
                }
            }
            val result = addMarkers(it)
            binding.vMap.tag = result

            updateSelectedItem()
            updateBenchmarkLocation()
        }

        viewModel.mapLocation.observe(this) {
            // update map location
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder().zoom(17.5f)
                        .target(LatLng(it.latitude!!, it.longitude!!)).build()
                )
            )
            updateSelectedItem()
            updateBenchmarkLocation()
        }

        map.setOnMarkerClickListener {
            viewModel.clickMapMarker(it.tag as GeodeticLocation)
        }

        viewModel.droneLocation.observe(this) { loc ->
            "Drone Location: ${formatLocation(loc.latitude)} / ${formatLocation(loc.longitude)} / ${
                formatLocation(
                    loc.height
                )
            }".also { binding.tvDroneLocation.text = it }
        }

        viewModel.gapDistance.observe(this) { gap ->
            "Horizontal/Vertical Distance (m): ${formatLocation(gap.first)} / ${formatLocation(gap.second)}".also {
                binding.tvGapDistance.text = it
            }
        }
        viewModel.recordStatus.observe(this) { status ->
            binding.btnStartRecord.text = if (status) "STOP" else "Record"
        }

        viewModel.recordFilePath.observe(this) { path ->
            "GPS Records file path: ${path ?: "--"}".also { binding.tvFilePath.text = it }
        }
    }

    private fun updateSelectedItem() {
        var index = 0

        viewModel.geodeticPosList.value?.let {
            for (i in it.indices) {
                val pos = it[i]

                if (pos.latitude == viewModel.mapLocation.value?.latitude
                    && pos.longitude == viewModel.mapLocation.value?.longitude
                ) {
                    index = i + 1
                    break
                }
            }
        }
        binding.spinnerLocations.setSelection(index)
    }

    private fun updateBenchmarkLocation() {
        val loc = viewModel.mapLocation.value

        "Benchmark Location: ${formatLocation(loc?.latitude)} / ${formatLocation(loc?.longitude)} / ${formatLocation(loc?.height)}".also {
            binding.tvBenchmarkLocation.text = it
        }
    }

    private fun addMarkers(locations: List<GeodeticLocation>): List<Marker> {
        val markers = mutableListOf<Marker>()

        locations.forEach {
            val marker = map.addMarker(
                MarkerOptions().position(LatLng(it.latitude, it.longitude))
                    .title(it.geodeticCode)
            )
            marker.tag = it
            markers.add(marker)
        }

        return markers
    }

    private fun initViews() {
        binding.spinnerLocations.adapter = object : BaseAdapter() {
            override fun getCount(): Int {
                return (viewModel.geodeticPosList.value?.size ?: 0) + 1
            }

            override fun getItem(position: Int): Any? {
                return if (0 == position) null else viewModel.geodeticPosList.value?.get(position - 1)
            }

            override fun getItemId(position: Int): Long {
                return getItem(position)?.hashCode()?.toLong() ?: 0
            }

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup?
            ): View? {
                var view = convertView
                if (view == null) {
                    view = LayoutInflater.from(this@GPSMeasurementActivity)
                        .inflate(R.layout.item_monitoring_status, parent, false)
                    view.setPadding(resources.getDimensionPixelSize(R.dimen.uxsdk_10_dp))
                    (view as TextView).setTextColor(Color.BLACK)
                }
                (view as TextView).let {
                    if (position == 0) {
                        it.text = "--"
                    } else {
                        it.text = (getItem(position) as? GeodeticLocation)?.name ?: ""
                    }
                }

                return view
            }
        }
        binding.spinnerLocations.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val location = parent?.getItemAtPosition(position) as? GeodeticLocation?
                    viewModel.selectLocation(location)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                }

            }
        binding.btnStartRecord.setOnClickListener {
            viewModel.startOrStopRecord()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityGpsMeasurementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initViews()

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