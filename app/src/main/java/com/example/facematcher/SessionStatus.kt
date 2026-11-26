package com.example.facematcher

data class SessionStatus(
    val phase: Phase = Phase.STOPPED,
    val camera: String = "Off",
    val microphone: String = "Off",
    val speech: String = "Off"
) {
    enum class Phase { STOPPED, STARTING, ACTIVE, UNAVAILABLE }
}
