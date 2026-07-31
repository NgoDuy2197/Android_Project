package com.example.cameraman

/**
 * In-process pub/sub so the background [CameraCaptureService] can push state
 * changes (capture finished, recording started/stopped, motion detected) to
 * [MainActivity], which forwards them to Flutter over an EventChannel. Both
 * live in the same process, so a shared object beats a broadcast here.
 */
object CaptureBus {

    @Volatile
    var listener: ((Map<String, Any?>) -> Unit)? = null

    // Cached so a freshly-opened UI can render the current state immediately.
    @Volatile var recording: Boolean = false
        private set

    @Volatile var motionRunning: Boolean = false
        private set

    fun emit(event: Map<String, Any?>) {
        when (event["event"]) {
            "recording" -> recording = event["value"] as? Boolean ?: recording
            "motion" -> motionRunning = event["value"] as? Boolean ?: motionRunning
        }
        listener?.invoke(event)
    }
}
