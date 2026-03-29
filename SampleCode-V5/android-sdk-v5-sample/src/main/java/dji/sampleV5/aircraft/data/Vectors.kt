package dji.sampleV5.aircraft.data

import dji.sampleV5.aircraft.utils.format


data class Vector2D(
    var x: Float,
    var y: Float
) {
    fun to3D(): Vector3D {
        return Vector3D(x, y, 0.0f)
    }

    constructor(): this(0.0f, 0.0f)

    override fun toString(): String {
        return "{x: ${x.format()}, y: ${y.format()}}"
    }
}

data class Vector3D(var x: Float, var y: Float, var z: Float) {
    fun to2D(): Vector2D {
        return Vector2D(x, y)
    }

    constructor(data: FloatArray) : this(data[0], data[1], data[2])

    constructor(data: DoubleArray) : this(data.first().toFloat(), data[1].toFloat(), data[2].toFloat())

    constructor() : this(0.0f, 0.0f, 0.0f)

    override fun toString(): String {
        return "{x: ${x.format()}, y: ${y.format()}, z: ${z.format()}}"
    }
}