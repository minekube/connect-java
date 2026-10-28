package com.minekube.connect.addon.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Guards the post-1.20.4 fake login cycle against finishing a login that Paper has already refused.
 *
 * <p>Defect reproduced against connect-spot 0.15.17 (Paper 26.3, case t_dc27dadd §4): the connector
 * called {@code callPlayerPreLoginEvents} - which lets Paper fire {@code AsyncPlayerPreLoginEvent} /
 * {@code PlayerLoginEvent} and issue its own {@code Disconnect} for any denial - and then called
 * {@code startClientVerification} unconditionally, driving {@code LoginSuccess} onto a connection
 * Paper had already ended. Whatever the client then sees, it is not Paper's kick message.
 *
 * <p>The refusal must be honoured: no verification call, no rewritten kick text, and the injection
 * reported as not handed over. These tests drive the real flow method, so the ordering of the two
 * reflective calls is under test rather than assumed.
 */
class SpigotLoginRefusalTest {
    private static final GameProfile PROFILE =
            new GameProfile(UUID.fromString("0a1b2c3d-4e5f-4071-8293-a4b5c6d7e8f9"), "NotListed");

    @Test
    void doesNotStartClientVerificationWhenPaperRefusedDuringThePreLoginEvents() throws Exception {
        FakeLoginListener listener = new FakeLoginListener();
        listener.finishWithKick("You are not whitelisted on this server!");

        boolean handedOver = SpigotDataHandler.runPostLoginInjection(listener, PROFILE, listener::open);

        assertFalse(handedOver, "a refused login must not be reported as handed over");
        assertTrue(listener.verified.isEmpty(),
                "startClientVerification must not run after Paper's refusal: " + listener.verified);
        assertEquals("You are not whitelisted on this server!", listener.kickMessage,
                "Paper's own kick text must reach the client unmodified");
    }

    @Test
    void doesNotStartClientVerificationWhenThePreLoginCallReturnsNoProfile() throws Exception {
        FakeLoginListener listener = new FakeLoginListener();
        listener.returnedProfile = null;
        listener.connectionStaysOpen = true;

        boolean handedOver = SpigotDataHandler.runPostLoginInjection(listener, PROFILE, listener::open);

        assertFalse(handedOver, "a null pre-login result means Paper did not hand the login back");
        assertTrue(listener.verified.isEmpty(), listener.verified.toString());
    }

    @Test
    void handsTheProfilePaperReturnedToVerificationOnAnAllowedLogin() throws Exception {
        FakeLoginListener listener = new FakeLoginListener();

        boolean handedOver = SpigotDataHandler.runPostLoginInjection(listener, PROFILE, listener::open);

        assertTrue(handedOver);
        assertEquals(1, listener.verified.size(), listener.verified.toString());
        assertSame(listener.returnedProfile, listener.verified.get(0),
                "the profile Paper returned must be the one verified");
    }

    @Test
    void verifiesTheRequestedProfileWhenPaperEchoesItBackUnchanged() throws Exception {
        FakeLoginListener listener = new FakeLoginListener();

        boolean handedOver = SpigotDataHandler.runPostLoginInjection(listener, PROFILE, listener::open);

        assertTrue(handedOver);
        assertEquals(List.of(PROFILE), listener.verified);
        assertTrue(listener.kickMessage == null, "an allowed login must not be kicked");
    }

    /**
     * Stands in for Paper's {@code ServerLoginPacketListenerImpl}: the pre-login call can end the
     * connection (and record Paper's kick message) exactly like {@code disconnect(String)} does.
     */
    private static final class FakeLoginListener implements SpigotDataHandler.PostLoginListener {
        private final AtomicBoolean connectionOpen = new AtomicBoolean(true);
        private final List<GameProfile> verified = new ArrayList<>();
        private GameProfile returnedProfile = PROFILE;
        private String kickMessage;
        private boolean connectionStaysOpen;

        void finishWithKick(String message) {
            this.kickMessage = message;
            this.connectionOpen.set(false);
        }

        boolean open() {
            return connectionOpen.get();
        }

        @Override
        public GameProfile callPlayerPreLoginEvents(GameProfile requested) {
            if (kickMessage != null && !connectionStaysOpen) {
                // Paper's disconnect(): the connection is gone once the event was denied.
                connectionOpen.set(false);
            }
            return returnedProfile;
        }

        @Override
        public void startClientVerification(GameProfile profile) {
            verified.add(profile);
        }
    }
}
