package dji.sampleV5.aircraft.virtualcontroller

import dji.sampleV5.aircraft.data.Vector3D


interface IPositionMonitor {

    fun getPosition(): Vector3D

    fun getRotation(): Vector3D

    fun start()

    fun stop()

    fun isMonitoring(): Boolean
}