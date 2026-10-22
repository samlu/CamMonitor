package com.a0soft.gphone.camonitor.permission

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying permission state tracking, rationale dialog triggers,
 * permanent denial detection, and onResume auto-recovery flow for B-09.
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

        fun checkAndRequest(
            required: List<String>,
            onGranted: () -> Unit
        ) {
            val needed = required.filter { it !in grantedPermissions }
            if (needed.isEmpty()) {
                onGranted()
            } else {
                pendingAction = onGranted
                val permanentlyDenied = needed.any { it !in rationalePermissions }
                if (permanentlyDenied && deniedPermissions.any { it in needed }) {
                    deniedPermissions = needed
                    isPermanentlyDenied = true
                    showRationaleDialog = true
                } else {
                    // Simulate permission launcher result with current grantedPermissions state
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

        fun onResume() {
            if (deniedPermissions.isNotEmpty()) {
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
    fun testMissingPermissionsDeferActionAndShowRationale() {
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = setOf("android.permission.CAMERA")
        )
        var actionExecuted = false

        manager.checkAndRequest(listOf("android.permission.CAMERA")) {
            actionExecuted = true
        }

        assertFalse("Action must not execute when permissions are denied", actionExecuted)
        assertTrue("Rationale dialog must be shown", manager.showRationaleDialog)
        assertFalse("Transient denial must not be marked permanently denied", manager.isPermanentlyDenied)
        assertEquals(listOf("android.permission.CAMERA"), manager.deniedPermissions)
        assertNotNull("Pending action must be preserved", manager.pendingAction)
    }

    @Test
    fun testPermanentDenialTriggersSettingsRoute() {
        // When not in rationale permissions, shouldShowRequestPermissionRationale returns false (permanent denial)
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = emptySet()
        )
        var actionExecuted = false

        manager.checkAndRequest(listOf("android.permission.CAMERA")) {
            actionExecuted = true
        }

        assertFalse("Action must not execute", actionExecuted)
        assertTrue("Rationale dialog must be displayed", manager.showRationaleDialog)
        assertTrue("Permanent denial flag must be true", manager.isPermanentlyDenied)
    }

    @Test
    fun testOnResumeAutoRecoveryExecutesPendingAction() {
        val manager = FakePermissionManager(
            grantedPermissions = emptySet(),
            rationalePermissions = setOf("android.permission.CAMERA")
        )
        var actionExecuted = false

        manager.checkAndRequest(listOf("android.permission.CAMERA")) {
            actionExecuted = true
        }

        assertFalse(actionExecuted)
        assertTrue(manager.showRationaleDialog)

        // User goes to Android App Settings and grants CAMERA permission, then returns (onResume)
        manager.grantedPermissions = setOf("android.permission.CAMERA")
        manager.onResume()

        assertTrue("Pending action must auto-execute on onResume once permissions are satisfied", actionExecuted)
        assertFalse("Rationale dialog must be dismissed", manager.showRationaleDialog)
        assertTrue("Denied permissions list must be empty", manager.deniedPermissions.isEmpty())
        assertNull("Pending action must be consumed", manager.pendingAction)
    }
}
