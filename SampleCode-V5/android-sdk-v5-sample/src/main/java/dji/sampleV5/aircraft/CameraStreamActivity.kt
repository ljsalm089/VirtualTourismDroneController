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
            Log.d("CameraStream", "OnFrame of framelistener is called")
            Log.d("CameraStream", "Frame data size: " + frameData.size + " - (first 10 bytes): ${frameData.take(10).joinToString("") { "%02x".format(it) }}") // want to see the ByteArray
            //modifyGreenChannel(frameData, offset, width, height) // we will uncomment later when we know the stream works
            Log.d("CameraStream", "")
            // draws the frame into the SurfaceView
            drawFrameOnSurface(frameData, offset, width, height) //will uncomment when written the method
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { //
//        Log.d("CameraStream", "On Create started for CameraStreamActivity")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_stream)
        surfaceView = findViewById(R.id.surfaceView) //make sure to set surface view ID in the layout file
        //allows event handling when stuff happens to the surface view such as is created, changed and destroyed. (every frame of teh camera will change it)
        surfaceView.holder.addCallback(this)
//        Log.d("CameraStream", "On Create fully ran for CameraStreamActivity")

    }

    override fun surfaceCreated(holder: SurfaceHolder) {
//        Log.d("CameraStream", "surfaceCreated method started")
        surface = holder.surface
        // here we start listening to the incoming frames
        cameraStreamManager.addFrameListener(
            cameraIndex,
            ICameraStreamManager.FrameFormat.RGBA_8888,
//            ICameraStreamManager.FrameFormat.NV21, //testing difernt format as per Jacobs idea
            frameListener
        )
//        Log.d("CameraStream", "surfaceCreated method over")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        //if we need to handle rotations and other stuff
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // clean up when the surface is destroyed
        surface = null
        cameraStreamManager.removeFrameListener(frameListener)
        Log.d("CameraStream", "frameListener removed")
    }

    private fun modifyGreenChannel(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        Log.d("CameraStream", "First line of modifyGreenChannel method")

        // Validate parameters first
        if (offset < 0) {
            Log.e("CameraStream", "Invalid negative offset: $offset")
            return
        }

        // Add counter for log throttling
        var iterationCount = 0

        // Process pixels assuming 4-byte format (e.g., RGBA)
        // Now starts from 0 instead of offset, with step=4
        for (i in 0 until (frameData.size - 1) step 4) {
            // Only log every 200,000 iterations
            if (iterationCount++ % 200000 == 0) {
                Log.d("CameraStream", "Processing iteration $iterationCount")
                Log.d("CameraStream", "i: $i")
            }

            // Safety check for array bounds
            if (i + 1 >= frameData.size) {
                Log.w("CameraStream", "Index out of bounds at i: $i")
                break
            }

            // Get and modify green channel
            val greenValue = frameData[i + 1]
            val newGreen = (greenValue + 20).coerceAtMost(0xFE).toByte()

            if (iterationCount % 200000 == 0) {
                Log.d("CameraStream", "Original green: $greenValue, New green: $newGreen")
            }

            frameData[i + 1] = newGreen
        }

        Log.d("CameraStream", "Completed processing. Total iterations: $iterationCount")
    }

    private fun drawFrameOnSurface(frameData: ByteArray, offset: Int, width: Int, height: Int) {
        runOnUiThread { // is this nessessary or AI bullshit?
            surface?.let { surface ->
                try {
//                    Log.d("CameraStream", "Made it to the try loop of the drawFrameOnSurface")
//                    Log.d("CameraStream", "Frame data: ${frameData.joinToString("") { "%02x".format(it) }}") // want to see the ByteArray
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//                    Log.d("CameraStream", "bitmap val set up")
//                    val buffer = ByteBuffer.wrap(frameData, offset, width * height * 4) // trying old way and commenting out the first way - post test (this one makes us error earlier with null error) - this one is baad dont use!!!!
                    val buffer = ByteBuffer.wrap(frameData, offset, frameData.size) // again how does this offset work? although it seems like they want the offset seperatly so maybe it is right?
//                    Log.d("CameraStream", "buffer val created")
                    bitmap.copyPixelsFromBuffer(buffer) //currently getting an error here - Buffer not large enough for pixels - offset problem?
//                    Log.d("CameraStream", "bitmap copied from buffer")

                    val canvas = surface.lockCanvas(null) //what is the canvas for and what does this do  - we lock the canvas to be able to draw on it and later unlock it
//                    Log.d("CameraStream", "canva setup")
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
//                    Log.d("CameraStream", "bitmap drawn on canvas")
                    surface.unlockCanvasAndPost(canvas)
//                    Log.d("CameraStream", "canvas unlocked")
                    bitmap.recycle() //free the memory
//                    Log.d("CameraStream", "bitmap freed")
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