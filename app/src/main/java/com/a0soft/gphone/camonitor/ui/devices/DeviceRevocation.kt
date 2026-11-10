package com.a0soft.gphone.camonitor.ui.devices

/**
 * Single revocation path shared by every unpair entry point (Manage Paired Devices, Home quick
 * remove). Drops the whitelist record first so no new signaling is accepted, then tears down any
 * live WebRTC peer so an already-connected stream/DataChannel ends immediately.
 */
fun revokePairedDevice(
    deviceId: String,
    removeRecord: (String) -> Unit,
    closePeer: (String) -> Unit
) {
    removeRecord(deviceId)
    closePeer(deviceId)
}
