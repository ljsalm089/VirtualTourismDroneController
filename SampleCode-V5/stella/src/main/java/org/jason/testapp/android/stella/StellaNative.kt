package org.jason.testapp.android.stella

class StellaNative constructor() {

    companion object {
        init {
            System.loadLibrary("opencv_java4")
            System.loadLibrary("native-lib")
        }
    }

    external fun initialize()

    external fun destroy()

}