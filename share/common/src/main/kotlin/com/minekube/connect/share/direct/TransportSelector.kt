package com.minekube.connect.share.direct

import arrow.core.Either
import arrow.core.left
import arrow.core.right

enum class ShareRoute {
    DIRECT_LAN,
    DIRECT_INTERNET,
    CONNECT,
}

object TransportSelector {
    fun plan(
        sameLan: Boolean,
        hostInternetOptIn: Boolean,
        guestInternetOptIn: Boolean,
        connectAddress: String?,
    ): List<ShareRoute> = buildList {
        if (sameLan) {
            add(ShareRoute.DIRECT_LAN)
        }
        if (hostInternetOptIn && guestInternetOptIn) {
            add(ShareRoute.DIRECT_INTERNET)
        }
        if (!connectAddress.isNullOrBlank()) {
            add(ShareRoute.CONNECT)
        }
    }
}

sealed interface ShareJoinError {
    val safeMessage: String

    data object RouteUnavailable : ShareJoinError {
        override val safeMessage = "This Connect Share route is unavailable"
    }

    data object NoRoute : ShareJoinError {
        override val safeMessage =
            "No direct route was available and Minekube Connect is not enabled"
    }
}

class ShareJoinCoordinator(
    private val attempt:
        suspend (ShareRoute) -> Either<ShareJoinError, Unit>,
) {
    suspend fun join(
        routes: List<ShareRoute>,
    ): Either<ShareJoinError, ShareRoute> {
        for (route in routes.distinct()) {
            when (attempt(route)) {
                is Either.Left -> Unit
                is Either.Right -> return route.right()
            }
        }
        return ShareJoinError.NoRoute.left()
    }
}
