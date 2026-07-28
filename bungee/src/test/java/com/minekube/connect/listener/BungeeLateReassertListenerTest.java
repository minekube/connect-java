package com.minekube.connect.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.junit.jupiter.api.Test;

/**
 * Pins BungeeCord's half of the ordering lever.
 *
 * <p>{@code EventPriority} is not an enum - it is a set of {@code byte} constants, the highest of
 * which is {@code HIGHEST = 64}, while {@code EventHandler#priority()} is a plain {@code byte}
 * and BungeeCord's event bus bakes handlers across the entire byte range. So
 * {@link Byte#MAX_VALUE} is inside the annotation's declared domain and runs strictly after
 * {@code HIGHEST}.
 *
 * <p>This is the only correct lever on BungeeCord: handlers of equal priority live in a
 * {@code HashMap} keyed by listener identity, so the tie-break is not registration order and no
 * {@code softDepends} in {@code plugin.yml} can influence it - unlike on Velocity, where the
 * declared dependency graph is a real fallback.
 */
class BungeeLateReassertListenerTest {
    @Test
    void theReassertRunsAfterEveryOtherPluginsPreLoginHandler() throws Exception {
        Method handler =
                BungeeLateReassertListener.class.getMethod("onPreLoginLate", PreLoginEvent.class);

        byte priority = handler.getAnnotation(EventHandler.class).priority();

        assertEquals(Byte.MAX_VALUE, priority,
                "the re-assert must run after HIGHEST; nothing lower is a floor");
        assertTrue(priority > EventPriority.HIGHEST);
    }

    /**
     * The re-assert only adds a floor. Connect's original pre-login handler keeps running first,
     * so Connect's ordering relative to every other plugin is otherwise unchanged.
     */
    @Test
    void theOriginalPreLoginHandlerStillRunsFirst() throws Exception {
        Method original = BungeeListener.class.getMethod("onPreLogin", PreLoginEvent.class);

        assertEquals(EventPriority.LOWEST, original.getAnnotation(EventHandler.class).priority());
    }
}
