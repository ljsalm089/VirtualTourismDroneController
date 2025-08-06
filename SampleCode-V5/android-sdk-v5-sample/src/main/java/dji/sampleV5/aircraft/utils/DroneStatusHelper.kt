package dji.sampleV5.aircraft.utils

import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.v5.et.create
import dji.v5.et.listen
import dji.v5.manager.KeyManager


interface DroneStatusCallback {

    fun onChange(key: DJIKeyInfo<*>, value: Any?)

}

class DroneStatusHelper(val subscribedStatusList: List<DJIKeyInfo<*>>, val callback: DroneStatusCallback) {

    fun startListen() {
        for (key in subscribedStatusList) {
            key.create().listen(this) {
                callback.onChange(key, it)
            }
        }
    }

    fun stopListen() {
        KeyManager.getInstance().cancelListen(this)
    }
}