package dji.sampleV5.aircraft

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import java.nio.ByteBuffer

class CameraStreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView // where we put the surface
    private var surface: Surface? = null // how we draw the camera stream
    private val cameraStreamManager: ICameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager // we need the camera manager to use camera functions
    private val cameraIndex = ComponentIndexType.LEFT_OR_MAIN // which camera we end up using MAY NEED TO TWEAK IF I HAVE THE WRONG CAM


    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat
        ) {
//            modifyGreenChannel(frameData, offset, width, height) // we will uncomment later when we know the stream works

            // draws the frame into the SurfaceView
//            drawFrameOnSurface(frameData, offset, width, height) //will uncomment when written the method
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) { //
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_stream)
        surfaceView = findViewById(R.id.surfaceView) //make sure to set surface view ID in the layout file
        //allows event handling when stuff happens to the surface view such as is created, changed and destroyed. (every frame of teh camera will change it)
        surfaceView.holder.addCallback(this)

    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        // here we start listening to the incoming frames
        cameraStreamManager.addFrameListener(
            cameraIndex,
            ICameraStreamManager.FrameFormat.RGBA_8888,
            frameListener
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        //if we need to handle rotations and other stuff
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // clean up when the surface is destroyed
        surface = null
        cameraStreamManager.removeFrameListener(frameListener)
    }

    private fun modifyGreenChannel(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        for (i in offset until offset + width * height * 4 step 4) { //find out what the offset really means in the bytesteam
            // should it be this - for (i in 0 until width * height * 4 step offset) - this feels like what Jacob explained
            // need to find out what DJI means by byteStream and then use it accordingly - maybe ill try these two though just to see if they are right
            val greenValue = frameData[i + 1] // this gets me the  value for the green component of the pixel
            val newGreen = (greenValue + 20).coerceAtMost(0xFF).toByte()
            frameData[i + 1] = newGreen
        }
    }

    private fun drawFrameOnSurface(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        runOnUiThread { // is this nessessary or AI bullshit?
            surface?.let { surface ->
                try {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val buffer = ByteBuffer.wrap(frameData, offset, width * height * 4) // again how does this offset work? although it seems like they want the offset seperatly so maybe it is right?
                    // val buffer = ByteBuffer.wrap(frameData, offset, width * height * offset) // should this be it
                    bitmap.copyPixelsFromBuffer(buffer)

                    val canvas = surface.lockCanvas(null) //what is the canvas for and what does this do  - we lock the canvas to be able to draw on it and later unlock it
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    surface.unlockCanvasAndPost(canvas)
                    bitmap.recycle() //free the memory
                } catch (e: Exception) {
                    Log.e("CameraStream", "Failed to draw frame: ${e.message}")
                }
            } ?: Log.e("CameraStream", "Surface is null") // elvis operator! this happens if the whole surface?.let thing doesnt work
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cleanup
        cameraStreamManager.removeFrameListener(frameListener)
    }

}