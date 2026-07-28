package com.minekube.connect.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.minekube.connect.api.logger.ConnectLogger;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import org.junit.jupiter.api.Test;

class VelocityListenerRegistrationTest {
    @Test
    void lateRegistrationContainsThrowableAndLogsDegradedBehaviour() {
        EventManager eventManager = mock(EventManager.class);
        ConnectLogger logger = mock(ConnectLogger.class);
        AssertionError failure = new AssertionError("registration failed");
        doThrow(failure).when(eventManager).register(
                any(), eq(PreLoginEvent.class), eq(PostOrder.LAST),
                org.mockito.ArgumentMatchers.<EventHandler<PreLoginEvent>>any());

        VelocityListenerRegistration registration =
                new VelocityListenerRegistration(eventManager, null, logger);

        assertDoesNotThrow(() -> registration.register(new VelocityLateReassertListener()));

        verify(logger).error(contains("login re-assert"), same(failure));
    }

    @Test
    void ordinaryListenerRegistrationIsUnchanged() {
        EventManager eventManager = mock(EventManager.class);
        ConnectLogger logger = mock(ConnectLogger.class);
        Object listener = new Object();
        VelocityListenerRegistration registration =
                new VelocityListenerRegistration(eventManager, null, logger);

        registration.register(listener);

        verify(eventManager).register(isNull(), same(listener));
    }
}
