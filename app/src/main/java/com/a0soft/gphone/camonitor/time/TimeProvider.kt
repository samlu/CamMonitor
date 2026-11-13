package com.a0soft.gphone.camonitor.time

import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

interface TimeProvider {
    fun currentTimeMillis(): Long
    suspend fun delayMillis(timeMillis: Long)
}

class DefaultTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override suspend fun delayMillis(timeMillis: Long) = delay(timeMillis.milliseconds)
}
