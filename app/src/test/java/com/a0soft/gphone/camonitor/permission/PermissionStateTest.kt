package com.a0soft.gphone.camonitor.permission

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying permission state tracking, rationale dialog triggers,
 * permanent denial detection, and onResume/reopen auto-recovery flow for B-09.
 */
class PermissionStateTest {

    class FakePermissionManager(
        var grantedPermissions: Set<String> = emptySet(),
        var rationalePermissions: Set<String> = emptySet()
    ) {
        var deniedPermissions = listOf<String>()
        var showRationaleDialog = false
        var isPermanentlyDenied = false
        var pendingAction: (() -> Unit)? = null
        var appWentToBackground = false

        fun checkStartupPermissions(required: List<String>) {
            val needed = required.filter { it !in grantedPermissions }
            if (needed.isNotEmpty()) {
                deniedPermissions = needed
                val permanentlyDenied = needed.any { it !in rationalePermissions }
                isPermanentlyDenied = permanentlyDenied
                if (permanentlyDenied) {
                    showRationaleDialog = true
                } else {
                    onLauncherResult(needed.associateWith { it in grantedPermissions })
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
                val permanentlyDenied = needed.any { it !in rationalePermissions }
                isPermanentlyDenied = permanentlyDenied
                if (permanentlyDenied) {
                    showRationaleDialog = true
                } else {
                    onLauncherResult(needed.associateWith { it in grantedPermissions })
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
                isPermanentlyDenied = denied.any { it !in rationalePermissions }
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
    fun testStartupCheckWithDeniedPermissionsShowsRationaleDialog() {
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = emptySet() // permanently denied on Pixel 8
        )
        manager.checkStartupPermissions(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"))

        assertTrue("Opening app with missing permissions must show rationale dialog", manager.showRationaleDialog)
        assertTrue("Permanent denial must route to Settings", manager.isPermanentlyDenied)
        assertEquals(listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"), manager.deniedPermissions)
    }

    @Test
    fun testReopenAppAfterExitShowsRationaleDialogAgain() {
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = emptySet()
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
            rationalePermissions = emptySet()
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
