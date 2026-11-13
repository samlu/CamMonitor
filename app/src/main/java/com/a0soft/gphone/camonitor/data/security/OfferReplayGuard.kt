package com.a0soft.gphone.camonitor.data.security

/** Persistent per-sender storage for the last accepted Offer timestamp. */
interface OfferTimestampStore {
    fun get(senderId: String): Long?
    fun put(senderId: String, timestamp: Long)
    fun remove(senderId: String)
}

/**
 * Persisted per-sender high-water mark for SDP Offers.
 *
 * The in-memory nonce cache is lost on process restart, so a captured Offer could be replayed
 * inside the timestamp tolerance window right after a restart and wake the camera. This guard
 * accepts an Offer only if its timestamp is strictly greater than the last accepted Offer from the
 * same sender. It costs one small write per accepted Offer (never per ICE candidate).
 *
 * Known limitation: if a viewer's clock is stepped backwards after it sent a future-dated Offer,
 * its Offers are rejected until its clock catches up (bounded by the timestamp tolerance).
 */
class OfferReplayGuard(private val store: OfferTimestampStore) {
    private val lock = Any()

    fun accept(senderId: String, timestamp: Long): Boolean = synchronized(lock) {
        val last = store.get(senderId)
        if (last != null && (timestamp <= last)) {
            false
        } else {
            store.put(senderId, timestamp)
            true
        }
    }

    fun forget(senderId: String) = synchronized(lock) { store.remove(senderId) }
}
