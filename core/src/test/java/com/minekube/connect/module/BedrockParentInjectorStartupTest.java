package com.minekube.connect.module;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Names;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.bedrock.BedrockIdentityEnforcer;
import com.minekube.connect.config.ConfigHolder;
import com.minekube.connect.config.ConnectConfig;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the connect-java 0.12.0 Paper startup crash.
 *
 * <p>PR #57 wired the Bedrock identity graph ({@link BedrockIdentityEnforcer} and its
 * {@code BedrockIdentityKeyProvider}) with a hard dependency on {@link ConnectConfig}. That graph is
 * resolved by the platform injector while constructing the platform, i.e. <em>before</em>
 * {@code ConnectPlatform.init()} loads the config. {@code ConnectConfig} is only bound in the child
 * injector that {@code init()} creates via {@link ConfigLoadedModule}. Resolving the Bedrock graph
 * from the parent injector therefore forced Guice to create a just-in-time {@code ConnectConfig}
 * binding on the parent, which then collided with the child binding:
 *
 * <pre>
 * [Guice/JitBindingAlreadySet]: A just-in-time binding to ConnectConfig was already configured
 * on a parent injector at ConfigLoadedModule.config(ConfigLoadedModule.java:49)
 * </pre>
 *
 * <p>The connector crashed in {@code SpigotPlugin.onLoad()} before token initialization, so
 * {@code plugins/Connect/token.json} was never created. The fix makes the Bedrock graph resolve
 * config lazily through the parent-bound {@link ConfigHolder} so no {@code ConnectConfig} binding is
 * ever established on the parent injector.
 */
class BedrockParentInjectorStartupTest {

    /**
     * Mirrors the production ordering: a parent injector (with only pre-config bindings available)
     * resolves the Bedrock enforcer the way {@code SpigotPlatformModule.platformInjector(...)} does,
     * and only afterwards does {@code ConnectPlatform.init()} create the config-owning child injector.
     */
    @Test
    void resolvingBedrockGraphDoesNotBindConfigOnParentInjector() {
        Injector parent = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                // ConfigHolder is bound on the parent and populated by init() before config is read.
                bind(ConfigHolder.class).toInstance(new ConfigHolder());
                bind(ConnectLogger.class).toInstance(mock(ConnectLogger.class));
                bind(OkHttpClient.class)
                        .annotatedWith(Names.named("defaultHttpClient"))
                        .toInstance(new OkHttpClient());
            }
        });

        // The platform injector resolves the Bedrock enforcer before config load.
        parent.getInstance(BedrockIdentityEnforcer.class);

        // Regression guard: resolving the Bedrock graph must NOT create a ConnectConfig binding on
        // the parent, otherwise the child ConfigLoadedModule below fails with JitBindingAlreadySet.
        assertNull(
                parent.getExistingBinding(Key.get(ConnectConfig.class)),
                "resolving the Bedrock graph must not establish a ConnectConfig binding on the parent injector");

        // Reproduces ConnectPlatform.init(): the loaded config is bound in a child injector.
        ConnectConfig loaded = new ConnectConfig();
        Injector child = parent.createChildInjector(new ConfigLoadedModule(loaded));
        assertSame(loaded, child.getInstance(ConnectConfig.class));
    }
}
