package com.thesis.geckowifi.verification

import com.thesis.geckowifi.capability.Capabilities
import com.thesis.geckowifi.capability.Enforcer
import com.thesis.geckowifi.capability.TrafficObserver
import com.thesis.geckowifi.capability.PcapTrafficObserver
import com.thesis.geckowifi.data.model.VerificationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class VerificationCoordinator (
    private val engine: VerificationEngine,
    private val session: PortalSession,
    //private val observer: TrafficObserver = PcapTrafficObserver(),
    private val enforcer: Enforcer = Capabilities.enforcer(),
    private val strictMode: Boolean = false
) {/*
    fun start(scope: CoroutineScope) {
        observer.start { host ->
            scope.launch {
                val result = engine.verify(host)   // uses PortalSession internally
                if (strictMode && result.state == VerificationState.CONFLICT) {
                    enforcer.block(host)
                }
            }
        }
    }*/
}