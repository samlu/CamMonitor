package com.a0soft.gphone.camonitor.permission

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying permission state tracking, rationale dialog triggers,
 * permanent denial detection, and onResume/reopen auto-recovery flow for B-09 and R2-01.
 */
class PermissionStateTest {

    class FakePermissionManager(
        var grantedPermissions: Set<String> = emptySet(),
        var rationalePermissions: Set<String> = emptySet(),
        var requestedPermissions: MutableSet<String> = mutableSetOf()
    ) {
        var deniedPermissions = listOf<String>()
        var showRationaleDialog = false
        var isPermanentlyDenied = false
        var pendingAction: (() -> Unit)? = null
        var appWentToBackground = false
        var launchedLauncherDirectly = false

        fun isPermanentlyDenied(perm: String): Boolean {
            return perm in requestedPermissions && perm !in rationalePermissions
        }

        fun checkStartupPermissions(required: List<String>) {
            val needed = required.filter { it !in grantedPermissions }
            if (needed.isNotEmpty()) {
                deniedPermissions = needed
                val permanentlyDenied = needed.any { isPermanentlyDenied(it) }
                isPermanentlyDenied = permanentlyDenied
                if (permanentlyDenied) {
                    showRationaleDialog = true
                } else {
                    needed.forEach { requestedPermissions.add(it) }
                    launchedLauncherDirectly = true
                    // Simulate system launcher result: if not granted, update state
                    val result = needed.associateWith { it in grantedPermissions }
                    onLauncherResult(result)
                }
            } else {
                deniedPermissions = emptyList()
                showRationaleDialog = false
            }
        }

        fun checkAndRequest(
            required: List<String>,
            onGranted: () -> Unit
        ) {
            val needed = required.filter { it !in grantedPermissions }
            if (needed.isEmpty()) {
                onGranted()
            } else {
                pendingAction = onGranted
                deniedPermissions = needed
                val permanentlyDenied = needed.any { isPermanentlyDenied(it) }
                isPermanentlyDenied = permanentlyDenied
                if (permanentlyDenied) {
                    showRationaleDialog = true
                } else {
                    needed.forEach { requestedPermissions.add(it) }
                    launchedLauncherDirectly = true
                    val result = needed.associateWith { it in grantedPermissions }
                    onLauncherResult(result)
                }
            }
        }

        fun onLauncherResult(result: Map<String, Boolean>) {
            val denied = result.filterValues { !it }.keys.toList()
            if (denied.isEmpty()) {
                showRationaleDialog = false
                deniedPermissions = emptyList()
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            } else {
                deniedPermissions = denied
                isPermanentlyDenied = denied.any { isPermanentlyDenied(it) }
                showRationaleDialog = true
            }
        }

        fun onStop() {
            appWentToBackground = true
        }

        fun onResume(required: List<String>) {
            if (appWentToBackground) {
                appWentToBackground = false
                val hadPending = pendingAction
                checkStartupPermissions(required)
                if (deniedPermissions.isEmpty() && hadPending != null) {
                    pendingAction = null
                    hadPending.invoke()
                }
            } else if (deniedPermissions.isNotEmpty()) {
                val stillDenied = deniedPermissions.filter { it !in grantedPermissions }
                deniedPermissions = stillDenied
                if (stillDenied.isEmpty()) {
                    showRationaleDialog = false
                    val action = pendingAction
                    pendingAction = null
                    action?.invoke()
                }
            }
        }
    }

    @Test
    fun testFreshInstallLaunchesPermissionLauncherDirectlyWithoutSettingsDialog() {
        // On a fresh install:
        // - grantedPermissions is empty
        // - rationalePermissions is empty (shouldShowRequestPermissionRationale == false)
        // - requestedPermissions is empty (has_requested == false)
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = emptySet(),
            requestedPermissions = mutableSetOf()
        )

        val needed = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        val permDeniedBefore = needed.any { manager.isPermanentlyDenied(it) }
        assertFalse("Fresh install must NOT evaluate as permanently denied", permDeniedBefore)

        manager.checkStartupPermissions(needed)
        assertTrue("Fresh install must invoke permission launcher directly", manager.launchedLauncherDirectly)
        assertTrue("Permissions must be marked as requested once launched", manager.requestedPermissions.containsAll(needed))
    }

    @Test
    fun testStartupCheckWithPermanentlyDeniedPermissionsShowsSettingsRationaleDialog() {
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = emptySet(), // permanently denied (user checked Don't ask again)
            requestedPermissions = mutableSetOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        )
        manager.checkStartupPermissions(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"))

        assertTrue("Opening app with permanently denied permissions must show rationale dialog", manager.showRationaleDialog)
        assertTrue("Permanent denial must route to Settings", manager.isPermanentlyDenied)
        assertEquals(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), manager.deniedPermissions)
    }

    @Test
    fun testReopenAppAfterExitShowsRationaleDialogAgain() {
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = emptySet(),
            requestedPermissions = mutableSetOf("android.permission.CAMERA")
        )
        // 1. App open -> shows dialog
        manager.checkStartupPermissions(listOf("android.permission.CAMERA"))
        assertTrue(manager.showRationaleDialog)

        // 2. User taps Cancel on the dialog
        manager.showRationaleDialog = false
        assertFalse(manager.showRationaleDialog)

        // 3. User exits app (goes to background)
        manager.onStop()
        assertTrue(manager.appWentToBackground)

        // 4. User opens app again -> onResume re-evaluates startup permissions
        manager.onResume(listOf("android.permission.CAMERA"))
        assertTrue("Reopening app after exiting must show rationale dialog again", manager.showRationaleDialog)
    }

    @Test
    fun testGrantedPermissionsExecuteActionImmediately() {
        val manager = FakePermissionManager(
            grantedPermissions = setOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        )
        var actionExecuted = false

        manager.checkAndRequest(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
            actionExecuted = true
        }

        assertTrue("Action must execute immediately when permissions are already granted", actionExecuted)
        assertFalse("Rationale dialog must not appear", manager.showRationaleDialog)
        assertNull("Pending action must be clear", manager.pendingAction)
    }

    @Test
    fun testOnResumeAutoRecoveryWhenPermissionsGrantedInSettings() {
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = emptySet(),
            requestedPermissions = mutableSetOf("android.permission.CAMERA")
        )
        var actionExecuted = false

        manager.checkAndRequest(listOf("android.permission.CAMERA")) {
            actionExecuted = true
        }
        assertFalse(actionExecuted)
        assertTrue(manager.showRationaleDialog)

        // User goes to Settings, app goes to background
        manager.onStop()
        // User grants permission in Settings
        manager.grantedPermissions = setOf("android.permission.CAMERA")
        // User returns to app
        manager.onResume(listOf("android.permission.CAMERA"))

        assertTrue("Pending action must auto-execute on onResume once permissions are satisfied", actionExecuted)
        assertFalse("Rationale dialog must be dismissed", manager.showRationaleDialog)
        assertTrue("Denied permissions list must be empty", manager.deniedPermissions.isEmpty())
        assertNull("Pending action must be consumed", manager.pendingAction)
    }
}
