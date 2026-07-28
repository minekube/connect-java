package com.minekube.connect.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * The old-Velocity half of the ordering lever, exercised for real: this module compiles against
 * velocity-api 3.2.0-SNAPSHOT, which predates
 * {@code EventManager#register(Object, Class, short, EventHandler)}. So the reflective lookup
 * genuinely fails here and the fallback path is the one under test — exactly what happens on a
 * Velocity build older than 2024-09-16.
 *
 * <p>The other half — the numeric-priority path, and that it really does run after
 * {@code PostOrder.LAST} — is proven against a real {@code VelocityEventManager} in
 * {@code VelocityLateEventOrderTest} ({@code velocity/src/eventOrderTest}).
 */
class VelocityLateEventRegistrarTest {
    @Test
    void velocityApiWithoutTheShortOverloadFallsBackToPostOrderLast() {
        assertNull(VelocityLateEventRegistrar.shortRegisterMethod(),
                "velocity-api 3.2.0-SNAPSHOT has no short register overload; if this starts "
                        + "failing the compile-time API was bumped and the fallback is untested");

        RecordingEventManager eventManager = new RecordingEventManager();
        Object plugin = new Object();
        EventHandler<PreLoginEvent> handler = event -> { };

        boolean usedNumericPriority = new VelocityLateEventRegistrar(eventManager, plugin)
                .registerAfterLast(PreLoginEvent.class, handler);

        assertFalse(usedNumericPriority);
        assertEquals(1, eventManager.registrations.size());
        Registration registration = eventManager.registrations.get(0);
        assertSame(plugin, registration.plugin);
        assertSame(PreLoginEvent.class, registration.eventType);
        assertEquals(PostOrder.LAST, registration.postOrder);
        assertSame(handler, registration.handler);
    }

    /**
     * Velocity maps {@code PostOrder.LAST} to {@code Short.MIN_VALUE + 1}, deliberately leaving
     * one slot below it. Losing that relationship silently turns the floor into a tie.
     */
    @Test
    void afterLastSitsOneSlotBelowPostOrderLast() {
        assertEquals((short) (Short.MIN_VALUE + 1),
                (short) (VelocityLateEventRegistrar.AFTER_LAST + 1));
    }

    private static final class Registration {
        final Object plugin;
        final Class<?> eventType;
        final PostOrder postOrder;
        final EventHandler<?> handler;

        Registration(Object plugin, Class<?> eventType, PostOrder postOrder,
                EventHandler<?> handler) {
            this.plugin = plugin;
            this.eventType = eventType;
            this.postOrder = postOrder;
            this.handler = handler;
        }
    }

    private static final class RecordingEventManager implements EventManager {
        final List<Registration> registrations = new ArrayList<>();

        @Override
        public void register(Object plugin, Object listener) {
            throw new AssertionError("the late re-assert must not register as an annotated listener");
        }

        @Override
        public <E> void register(Object plugin, Class<E> eventClass, PostOrder postOrder,
                EventHandler<E> handler) {
            registrations.add(new Registration(plugin, eventClass, postOrder, handler));
        }

        @Override
        public <E> CompletableFuture<E> fire(E event) {
            return CompletableFuture.completedFuture(event);
        }

        @Override
        public void unregisterListeners(Object plugin) {
        }

        @Override
        public void unregisterListener(Object plugin, Object listener) {
        }

        @Override
        public <E> void unregister(Object plugin, EventHandler<E> handler) {
        }
    }
}
