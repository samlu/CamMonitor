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

    /** The hint fades on its own so an AMOLED panel shows no static bright element. */
    const val HINT_DELAY_MS = 1_500L

    const val LOCKED_BRIGHTNESS = 0.01f

    /**
     * Display keep-awake policy:
     * - Returns true when UNLOCKED (!isLocked): Keeps the display awake so live camera
     *   monitoring and preview never time out or sleep while the user is actively viewing.
     * - Returns false when LOCKED (isLocked == true): Clears FLAG_KEEP_SCREEN_ON so the
     *   physical display panel and display controller can turn off completely, allowing the
     *   device SoC to enter deep sleep (Doze).
     *
     * CAUTION: Never hold FLAG_KEEP_SCREEN_ON while locked. On modern OLED panels, even with
     * 100% black pixels (#000000) at 0.01 brightness, keeping the window awake blocks display
     * suspend (PowerManagerService.Display blocker), consuming ~180-240 mA continuously and
     * burning battery overnight. The touch guard (swallowing stray taps) is enforced in UI
     * via pointerInput and does NOT require keeping the display awake.
     */
    fun shouldKeepScreenOn(isLocked: Boolean): Boolean = !isLocked

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
