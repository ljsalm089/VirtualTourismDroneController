package dji.sampleV5.aircraft.webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch


const val TYPE_DATA = "data"

const val TYPE_AUDIO = "audio"

const val TYPE_VIDEO = "video"



class WebRtcManager(private val scope: CoroutineScope) {

    private lateinit var periodicJob: Job

    private val connections = HashSet<WebRtcConnection>()

    fun start() {


        executePeriodicTask()
    }

    fun stop() {
        if (this::periodicJob.isInitialized) {
            this.periodicJob.cancel()
        }

        for (connection: WebRtcConnection in connections) {
            connection.disconnect()
        }
        connections.clear()
    }

    private fun executePeriodicTask() {
        periodicJob = scope.launch {
            while (isActive) {
                // keep alive
                for (connection: WebRtcConnection in connections) {
                    connection.keepAlive()
                }

                delay(1000)
            }
        }
    }
}

class WebRtcConnection(val identity: String, val supportTypes: Set<String>, val offerExchange: IOfferExchange) {


    fun connect() {

    }

    fun keepAlive() {

    }

    fun disconnect() {

    }
}