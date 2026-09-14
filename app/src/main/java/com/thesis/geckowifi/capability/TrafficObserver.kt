package com.thesis.geckowifi.capability


interface TrafficObserver {
    fun start(onHost: (host: String) -> Unit)
    fun stop()
    val isActive: Boolean
}

object TrafficBus {
    @Volatile private var sink: ((String) -> Unit)? = null
    fun attach(s: (String) -> Unit) { sink = s }
    fun detach() { sink = null }
    fun publish(host: String) { sink?.invoke(host) }
}