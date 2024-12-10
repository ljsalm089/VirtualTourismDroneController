package dji.sampleV5.aircraft

import android.os.Bundle
import android.widget.Button
//import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dji.sampleV5.aircraft.pages.LiveFragment

class FragmentTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        enableEdgeToEdge()
        setContentView(R.layout.activity_fragment_test)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val liveFragment = LiveFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, liveFragment)
            .commit()

        val btnReturn = findViewById<Button>(R.id.btnReturn)
        btnReturn.setOnClickListener() {
            finish()
        }
    }
}