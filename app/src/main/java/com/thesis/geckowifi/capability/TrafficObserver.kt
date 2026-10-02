package com.thesis.geckowifi.capability


interface TrafficObserver {
    fun start(onHost: (host: String) -> Unit)
    fun stop()
    val isActive: Boolean
}
