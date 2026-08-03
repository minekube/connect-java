package com.minekube.connect.share.fabric

import com.minekube.connect.share.friend.FriendActivity
import com.minekube.connect.share.friend.FriendActivityKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FollowNextSessionControllerTest {
    @Test
    fun `joinable epoch emits one request and duplicate presence cannot storm`() {
        val controller = FollowNextSessionController(now = { NOW })
        controller.follow(ROBIN, "Robin")
        val activity = mapOf(
            ROBIN to FriendActivity(
                FriendActivityKind.HOSTING_WORLD,
                "Survival",
                joinable = true,
                sessionEpoch = "world-1",
            ),
        )

        assertEquals(
            listOf(FollowAction.RequestJoin(ROBIN, "Robin", "world-1")),
            controller.update(activity, activeGameplay = false, setOf(ROBIN)),
        )
        assertTrue(
            controller.update(activity, activeGameplay = false, setOf(ROBIN))
                .isEmpty(),
        )
    }

    @Test
    fun `active gameplay is never interrupted and receives one join offer`() {
        val controller = FollowNextSessionController(now = { NOW })
        controller.follow(ROBIN, "Robin")

        assertEquals(
            listOf(FollowAction.OfferJoinNow(ROBIN, "Robin", "server-1")),
            controller.update(
                mapOf(
                    ROBIN to FriendActivity(
                        FriendActivityKind.PLAYING_SERVER,
                        "Friends server",
                        sessionEpoch = "server-1",
                    ),
                ),
                activeGameplay = true,
                confirmedPeerIds = setOf(ROBIN),
            ),
        )
    }

    @Test
    fun `expiry cancellation and removal clear follow intent`() {
        var now = NOW
        val controller = FollowNextSessionController(
            now = { now },
            lifetimeSeconds = 60,
        )
        controller.follow(ROBIN, "Robin")
        assertTrue(controller.cancel(ROBIN))
        assertTrue(controller.state.value.isEmpty())

        controller.follow(ROBIN, "Robin")
        now = NOW.plusSeconds(61)
        assertEquals(
            listOf(FollowAction.Expired(ROBIN, "Robin")),
            controller.update(emptyMap(), false, setOf(ROBIN)),
        )

        controller.follow(ROBIN, "Robin")
        assertEquals(
            listOf(FollowAction.Cancelled(ROBIN, "Robin")),
            controller.update(emptyMap(), false, emptySet()),
        )
    }

    @Test
    fun `simultaneous follows remain independent`() {
        val controller = FollowNextSessionController(now = { NOW })
        controller.follow(ROBIN, "Robin")
        controller.follow(ALEX, "Alex")

        val actions = controller.update(
            mapOf(
                ROBIN to FriendActivity(
                    FriendActivityKind.HOSTING_WORLD,
                    sessionEpoch = "r1",
                ),
                ALEX to FriendActivity(
                    FriendActivityKind.HOSTING_WORLD,
                    sessionEpoch = "a1",
                ),
            ),
            activeGameplay = false,
            confirmedPeerIds = setOf(ROBIN, ALEX),
        )

        assertEquals(2, actions.size)
        assertEquals(setOf(ROBIN, ALEX), actions.map { it.peerId }.toSet())
    }

    @Test
    fun `blocking a friend cancels follow before any join request`() {
        val controller = FollowNextSessionController(now = { NOW })
        controller.follow(ROBIN, "Robin")

        val actions = controller.update(
            activities = mapOf(
                ROBIN to FriendActivity(
                    FriendActivityKind.HOSTING_WORLD,
                    sessionEpoch = "blocked-world",
                ),
            ),
            activeGameplay = false,
            confirmedPeerIds = emptySet(),
        )

        assertEquals(
            listOf(FollowAction.Cancelled(ROBIN, "Robin")),
            actions,
        )
        assertTrue(controller.state.value.isEmpty())
    }

    @Test
    fun `reconnect with a new world epoch can retry without duplicating either epoch`() {
        val controller = FollowNextSessionController(now = { NOW })
        controller.follow(ROBIN, "Robin")

        fun activity(epoch: String) = mapOf(
            ROBIN to FriendActivity(
                FriendActivityKind.HOSTING_WORLD,
                sessionEpoch = epoch,
            ),
        )

        assertEquals(1, controller.update(activity("world-1"), false, setOf(ROBIN)).size)
        assertTrue(controller.update(activity("world-1"), false, setOf(ROBIN)).isEmpty())
        assertEquals(1, controller.update(activity("world-2"), false, setOf(ROBIN)).size)
        assertTrue(controller.update(activity("world-2"), false, setOf(ROBIN)).isEmpty())
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-01T12:00:00Z")
        const val ROBIN = "12D3KooWRobin"
        const val ALEX = "12D3KooWAlex"
    }
}
