package dji.sampleV5.aircraft

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dji.sampleV5.aircraft.pages.CameraStreamDetailFragment
import dji.sampleV5.aircraft.pages.CameraStreamListFragment

class LiveStreamActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        setContentView(R.layout.activity_stream_live)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val cameraStreamDetailFragment = CameraStreamDetailFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, cameraStreamDetailFragment)
            .commit()

        val cameraStreamListFragment = CameraStreamListFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment2_container, cameraStreamListFragment)
            .commit()

    }
}