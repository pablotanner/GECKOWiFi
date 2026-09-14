package com.thesis.geckowifi.capability

interface Enforcer {
    suspend fun block(host: String)
    suspend fun unblock(host: String)
    suspend fun blockAllExcept(allowed: List<String>)
    suspend fun clear()
}