package com.minekube.connect;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.BedrockAdmissionCoordinator;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class SpigotPlatformConstructorTest {
    @Test
    void retainsFourArgumentConstruction() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        BedrockAdmissionCoordinator coordinator = new BedrockAdmissionCoordinator(registry);
        Injector injector = Guice.createInjector(
                binder -> binder.bind(BedrockAdmissionCoordinator.class).toInstance(coordinator));
        try {
            assertNotNull(new SpigotPlatform(null, null, null, injector));
        } finally {
            coordinator.close();
        }
    }

    @Test
    void injectsLifecycleOwnedCoordinator() throws Exception {
        Constructor<SpigotPlatform> constructor = SpigotPlatform.class.getConstructor(
                ConnectApi.class,
                PlatformInjector.class,
                ConnectLogger.class,
                Injector.class,
                BedrockAdmissionCoordinator.class);

        assertNotNull(constructor.getAnnotation(Inject.class));
    }
}
