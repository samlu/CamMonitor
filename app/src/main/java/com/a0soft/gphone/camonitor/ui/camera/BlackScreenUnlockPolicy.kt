package com.a0soft.gphone.camonitor.ui.camera

/**
 * Pure decision logic for the black-screen touch lock.
 *
 * The gesture itself lives in a Compose `pointerInput` block, which a plain JVM
 * unit test cannot reach (this module has no Robolectric or Compose test runner
 * on the unit-test classpath). Keeping the thresholds and the accept/reject rule
 * here makes the behaviour testable without an emulator, and leaves the UI layer
 * as wiring only.
 */
object BlackScreenUnlockPolicy {

    /** A tap that just locked the screen must not immediately unlock it. */
    const val LOCK_ARM_DELAY_MS = 300L

    /** After unlocking, ignore stray focus events that could re-lock the screen. */
    const val UNLOCK_COOLDOWN_MS = 3_000L

    /** The hint fades on its own so an AMOLED panel shows no static bright element. */
    const val HINT_DELAY_MS = 1_500L

    const val LOCKED_BRIGHTNESS = 0.01f
    const val UNLOCKED_BRIGHTNESS = 0.55f

    private const val SWIPE_FRACTION = 0.3f
    private const val MIN_SWIPE_PX = 120f

    /**
     * Required travel distance: 30% of the screen width, but never below 120 px.
     * A zero-width first layout must not turn any touch into an unlock, hence the
     * floor; a negative width is clamped for the same reason.
     */
    fun requiredSwipePx(screenWidthPx: Float): Float =
        maxOf(screenWidthPx.coerceAtLeast(0f) * SWIPE_FRACTION, MIN_SWIPE_PX)

    /**
     * True when a deliberate swipe should unlock: the lock has been armed long
     * enough and the pointer travelled far enough on either axis. Distance is
     * absolute so a swipe in any direction counts; the longer axis wins so a
     * diagonal gesture does not need twice the travel.
     */
    fun shouldUnlock(dx: Float, dy: Float, screenWidthPx: Float, sinceLockMs: Long): Boolean {
        if (sinceLockMs < LOCK_ARM_DELAY_MS) return false
        val travel = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        return travel >= requiredSwipePx(screenWidthPx)
    }
}
