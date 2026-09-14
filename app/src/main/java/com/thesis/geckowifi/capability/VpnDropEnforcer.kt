package com.thesis.geckowifi.capability

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Non-root enforcement: maintains a blocked-IP set the VPN packet loop consults
 * before forwarding. Enforcement is post-association, unlike IptablesEnforcer.
 */
class VpnDropEnforcer : Enforcer {

    private val blockedIps = ConcurrentHashMap.newKeySet<String>()
    private val blockedHosts = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var allowOnly: Set<String>? = null

    fun shouldDrop(destinationIp: String): Boolean {
        allowOnly?.let { return destinationIp !in it }
        return destinationIp in blockedIps
    }

    override suspend fun block(host: String) {
        blockedHosts.add(host.lowercase())
        blockedIps.addAll(resolve(host))
    }

    override suspend fun unblock(host: String) {
        blockedHosts.remove(host.lowercase())
        blockedIps.removeAll(resolve(host).toSet())
    }

    override suspend fun blockAllExcept(allowed: List<String>) {
        allowOnly = allowed.flatMap { resolve(it) }.toSet()
    }

    override suspend fun clear() {
        blockedIps.clear(); blockedHosts.clear(); allowOnly = null
    }

    private suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }
            .getOrDefault(emptyList())
    }
}