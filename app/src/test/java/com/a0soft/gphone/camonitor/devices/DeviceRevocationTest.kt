package com.a0soft.gphone.camonitor.devices

import com.a0soft.gphone.camonitor.ui.devices.revokePairedDevice
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRevocationTest {

    @Test
    fun revoke_RemovesRecordThenClosesPeer() {
        val calls = mutableListOf<String>()
        revokePairedDevice(
            deviceId = "cam-1",
            removeRecord = { calls.add("remove:$it") },
            closePeer = { calls.add("close:$it") }
        )
        assertEquals(listOf("remove:cam-1", "close:cam-1"), calls)
    }
}
