package com.ncm.app.ui.navigation

/**
 * Identifies the visible app surface for the purpose of dismissing a focused
 * software keyboard when that surface changes.
 */
internal data class KeyboardDismissalTarget(
    val route: String?,
    val playerOverlaySongId: Long?
)

internal fun shouldDismissKeyboardForTransition(
    previous: KeyboardDismissalTarget?,
    current: KeyboardDismissalTarget
): Boolean = previous != null && previous != current
