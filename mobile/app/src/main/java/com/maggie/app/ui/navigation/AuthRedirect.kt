package com.maggie.app.ui.navigation

enum class AuthRedirect { NONE, LOGIN, DASHBOARD }

/**
 * Where the signed-in state sends the app. [route] is the route the NavController is on
 * when the decision is taken, not the one Compose last observed: a deep link opened from a
 * cold start is pushed on the controller before the state that mirrors it has caught up,
 * and a decision taken on that stale `null` / Loading wipes the link.
 */
internal fun authRedirect(isAuthenticated: Boolean?, isLocked: Boolean, route: String?): AuthRedirect = when (isAuthenticated) {
    false -> if (route != Screen.Login.route) AuthRedirect.LOGIN else AuthRedirect.NONE
    true -> if (!isLocked && (route == null || route == Screen.Login.route || route == Screen.Loading.route)) {
        AuthRedirect.DASHBOARD
    } else {
        AuthRedirect.NONE
    }
    null -> AuthRedirect.NONE
}
