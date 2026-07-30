package com.minekube.connect.share.direct

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class TransportSelectorTest {
    @Test
    fun `same LAN is attempted before internet and Connect`() {
        val plan = TransportSelector.plan(
            sameLan = true,
            hostInternetOptIn = true,
            guestInternetOptIn = true,
            connectAddress = "amber-fox.play.minekube.net",
        )

        assertEquals(
            listOf(
                ShareRoute.DIRECT_LAN,
                ShareRoute.DIRECT_INTERNET,
                ShareRoute.CONNECT,
            ),
            plan,
        )
    }

    @Test
    fun `internet direct requires opt in from both peers`() {
        assertEquals(
            listOf(ShareRoute.CONNECT),
            TransportSelector.plan(
                sameLan = false,
                hostInternetOptIn = true,
                guestInternetOptIn = false,
                connectAddress = "amber-fox.play.minekube.net",
            ),
        )
        assertEquals(
            listOf(ShareRoute.CONNECT),
            TransportSelector.plan(
                sameLan = false,
                hostInternetOptIn = false,
                guestInternetOptIn = true,
                connectAddress = "amber-fox.play.minekube.net",
            ),
        )
    }

    @Test
    fun `failed direct attempts fall back to Connect exactly once`() = runTest {
        val attempts = mutableListOf<ShareRoute>()
        val result = ShareJoinCoordinator(
            attempt = { route ->
                attempts += route
                if (route == ShareRoute.CONNECT) {
                    Either.Right(Unit)
                } else {
                    Either.Left(ShareJoinError.RouteUnavailable)
                }
            },
        ).join(
            listOf(
                ShareRoute.DIRECT_LAN,
                ShareRoute.DIRECT_INTERNET,
                ShareRoute.CONNECT,
                ShareRoute.CONNECT,
            ),
        )

        assertEquals(ShareRoute.CONNECT, assertIs<Either.Right<ShareRoute>>(result).value)
        assertEquals(
            listOf(
                ShareRoute.DIRECT_LAN,
                ShareRoute.DIRECT_INTERNET,
                ShareRoute.CONNECT,
            ),
            attempts,
        )
    }

    @Test
    fun `no direct route and no Connect returns an actionable failure`() = runTest {
        val result = ShareJoinCoordinator {
            Either.Left(ShareJoinError.RouteUnavailable)
        }.join(listOf(ShareRoute.DIRECT_LAN))

        assertIs<Either.Left<ShareJoinError.NoRoute>>(result)
    }
}
