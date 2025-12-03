package dji.sampleV5.aircraft.utils

import com.google.gson.GsonBuilder
import java.lang.reflect.Type


fun Float.format(numberOfZero: Int = 2): String {
    return "%.${numberOfZero}f".format(this)
}

fun Double.format(numberOfZero: Int = 2): String {
    return "%.${numberOfZero}f".format(this)
}

fun Int.format(numberOfZero: Int = 2): String {
    return "%0${numberOfZero}d".format(this)
}

fun Any.toJson(): String {
    return Inner.gson.toJson(this)
}

fun <T> String.toData(clazz: Class<T>): T {
    return Inner.gson.fromJson<T>(this, clazz)
}

fun <T> String.toData(type: Type): T {
    return Inner.gson.fromJson(this, type)
}

private object Inner {

    val gson = GsonBuilder().create()

}

object LogLevel {

    val VERBOSE_DRONE_VELOCITY_READ_ACTIVELY = -10

    val VERBOSE_HEADSET_DRONE_VELOCITY_CHANGES = -11

    val VERBOSE_DRONE_POSITION_CHANGE_BASED_ON_VELOCITY = -12

}