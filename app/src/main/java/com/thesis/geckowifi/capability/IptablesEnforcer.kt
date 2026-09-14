package com.thesis.geckowifi.capability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

class IptablesEnforcer : Enforcer {
    private val chain = "GECKO_BLOCK"

    private suspend fun ensureChain() {
        RootShell.execAsync("iptables -N $chain 2>/dev/null; iptables -C OUTPUT -j $chain 2>/dev/null || iptables -I OUTPUT -j $chain")
    }

    override suspend fun block(host: String) {
        ensureChain()
        resolve(host).forEach { ip ->
            RootShell.execAsync("iptables -A $chain -d $ip -j REJECT")
        }
    }

    override suspend fun unblock(host: String) {
        resolve(host).forEach { ip ->
            RootShell.execAsync("iptables -D $chain -d $ip -j REJECT 2>/dev/null")
        }
    }

    override suspend fun blockAllExcept(allowed: List<String>) {
        ensureChain()
        RootShell.execAsync("iptables -F $chain")
        allowed.flatMap { resolve(it) }.forEach { ip ->
            RootShell.execAsync("iptables -A $chain -d $ip -j RETURN")
        }
        RootShell.execAsync("iptables -A $chain -j REJECT")
    }

    override suspend fun clear() {
        RootShell.execAsync("iptables -F $chain 2>/dev/null")
    }

    private suspend fun resolve(host: String): List<String> = withContext(Dispatchers.IO) {
        try { InetAddress.getAllByName(host).map { it.hostAddress } } catch (e: Exception) { emptyList() }
    }
}