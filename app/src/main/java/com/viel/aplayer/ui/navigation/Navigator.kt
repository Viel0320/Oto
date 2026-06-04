package com.viel.aplayer.ui.navigation

import androidx.navigation3.runtime.NavKey

/**
 * App Navigator (Handle navigation actions)
 * Acts as the event handler for forward and back navigation steps, altering NavigationState.
 */
class Navigator(val state: NavigationState) {
    // Navigate To Route (Redirect or push new route to active back stack)
    // Switches to topLevelRoute if specified, otherwise appends key to the current active stack.
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            state.topLevelRoute = route
        } else {
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    // Go Back (Pop current route or switch top-level parent route)
    // Removes the last destination from the stack, or switches back to the startRoute stack.
    fun goBack() {
        val currentStack = state.backStacks[state.topLevelRoute] ?:
        error("Stack for ${state.topLevelRoute} not found")
        val currentRoute = currentStack.last()

        if (currentRoute == state.topLevelRoute) {
            state.topLevelRoute = state.startRoute
        } else {
            currentStack.removeLastOrNull()
        }
    }
}
