package com.minekube.connect;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistry;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;

class SpigotPlatformConstructorTest {
    @Test
    void retainsFourArgumentConstruction() {
        VerifiedBedrockIdentityRegistry registry = new VerifiedBedrockIdentityRegistry();
        Injector injector = Guice.createInjector(
                binder -> binder.bind(VerifiedBedrockIdentityRegistry.class).toInstance(registry));
        try {
            assertNotNull(new SpigotPlatform(null, null, null, injector));
        } finally {
            registry.close();
        }
    }

    @Test
    void injectsLifecycleOwnedRegistry() throws Exception {
        Constructor<SpigotPlatform> constructor = SpigotPlatform.class.getConstructor(
                ConnectApi.class,
                PlatformInjector.class,
                ConnectLogger.class,
                Injector.class,
                VerifiedBedrockIdentityRegistry.class);

        assertNotNull(constructor.getAnnotation(Inject.class));
    }
}
