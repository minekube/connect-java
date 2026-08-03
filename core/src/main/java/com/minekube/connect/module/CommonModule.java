/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package com.minekube.connect.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.OptionalBinder;
import com.google.inject.name.Named;
import com.minekube.connect.api.ConnectApi;
import com.minekube.connect.api.SimpleConnectApi;
import com.minekube.connect.api.inject.PlatformInjector;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.packet.PacketHandlers;
import com.minekube.connect.bedrock.BedrockIdentityKeyProvider;
import com.minekube.connect.bedrock.BedrockIdentityReadiness;
import com.minekube.connect.bedrock.BedrockPrincipalReadiness;
import com.minekube.connect.config.ConfigHolder;
import com.minekube.connect.config.ConfigLoader;
import com.minekube.connect.config.ConfigLoader.EndpointNameGenerator;
import com.minekube.connect.config.ConnectConfig;
import com.minekube.connect.inject.CommonPlatformInjector;
import com.minekube.connect.identity.EndpointTokenStore;
import com.minekube.connect.packet.PacketHandlersImpl;
import com.minekube.connect.platform.util.PlatformUtils;
import com.minekube.connect.tunnel.TunnelClientTransport;
import com.minekube.connect.tunnel.WebSocketTunnelTransport;
import com.minekube.connect.tunnel.p2p.Libp2pTunnelTransport;
import com.minekube.connect.util.Constants;
import com.minekube.connect.util.HttpUtils;
import com.minekube.connect.util.LanguageManager;
import com.minekube.connect.util.Metrics;
import com.minekube.connect.watch.AllowAllSessionAdmissionGate;
import com.minekube.connect.watch.SessionAdmissionGate;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;

@RequiredArgsConstructor
public class CommonModule extends AbstractModule {
    private static final long WATCH_PING_INTERVAL_SECONDS = 30;

    private final Path dataDirectory;

    @Override
    protected void configure() {
        bind(ConnectApi.class).to(SimpleConnectApi.class);
        bind(PlatformInjector.class).to(CommonPlatformInjector.class);

        bind(PacketHandlers.class).to(PacketHandlersImpl.class);
        bind(PacketHandlersImpl.class).asEagerSingleton();
        Multibinder<TunnelClientTransport> transports =
                Multibinder.newSetBinder(binder(), TunnelClientTransport.class);
        transports.addBinding().to(WebSocketTunnelTransport.class);
        transports.addBinding().to(Libp2pTunnelTransport.class);
        OptionalBinder.newOptionalBinder(binder(), SessionAdmissionGate.class)
                .setDefault()
                .to(AllowAllSessionAdmissionGate.class);
    }

    @Provides
    @Singleton
    @Named("dataDirectory")
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Provides
    @Singleton
    public ConfigHolder configHolder() {
        return new ConfigHolder();
    }

    @Provides
    @Singleton
    public ConfigLoader configLoader(
            @Named("configClass") Class<? extends ConnectConfig> configClass,
            ConnectLogger logger,
            EndpointNameGenerator endpointNameGenerator
    ) {
        return new ConfigLoader(dataDirectory, configClass, endpointNameGenerator, logger);
    }

    @Provides
    @Singleton
    public LanguageManager languageLoader(
            ConfigHolder configHolder,
            ConnectLogger logger) {
        return new LanguageManager(configHolder, logger);
    }


    @Provides
    @Singleton
    @Named("defaultHttpClient")
    public OkHttpClient defaultOkHttpClient() {
        return HttpUtils.defaultOkHttpClient();
    }

    // BedrockIdentityKeyProvider is bound just-in-time (@Inject @Singleton) so it and the enforcer
    // can be resolved by the config-agnostic parent injector without pulling ConnectConfig into it.
    // See BedrockParentInjectorStartupTest.

    @Provides
    @Singleton
    public BedrockIdentityReadiness bedrockIdentityReadiness(
            ConfigHolder configHolder,
            BedrockIdentityKeyProvider keyProvider) {
        return new BedrockIdentityReadiness(configHolder.get(), keyProvider);
    }

    @Provides
    @Singleton
    public BedrockPrincipalReadiness bedrockPrincipalReadiness(ConfigHolder configHolder) {
        return new BedrockPrincipalReadiness(configHolder.get());
    }

    @Provides
    @Singleton
    public EndpointTokenStore endpointTokenStore() {
        return new EndpointTokenStore();
    }

    @Provides
    @Singleton
    @Named("connectToken")
    public String connectToken(EndpointTokenStore endpointTokenStore) throws IOException {
        return endpointTokenStore.loadOrCreate(
                dataDirectory.resolve("token.json"),
                System.getenv());
    }

    @Provides
    @Singleton
    @Named("connectHttpClient")
    public OkHttpClient connectOkHttpClient(
            @Named("defaultHttpClient") OkHttpClient defaultOkHttpClient,
            PlatformUtils platformUtils,
            @Named("platformName") String implementationName,
            ConnectApi api,
            @Named("connectToken") String apiToken
    ) {
        return defaultOkHttpClient.newBuilder()
                .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                        // Add authorization token to every request
                        .addHeader("Authorization", "Bearer " + apiToken)
                        // Add Connect Metadata to every request
                        .addHeader("Connect-TotalPlayers",
                                String.valueOf(platformUtils.getPlayerCount()))
                        .addHeader("Connect-Players", String.valueOf(api.getPlayerCount()))
                        .addHeader("Connect-Version", Constants.VERSION)
                        .addHeader("Connect-AuthType", platformUtils.authType().name())
                        .addHeader("Connect-Platform", implementationName)
                        .addHeader("Connect-Platform", platformUtils.serverImplementationName())
                        .addHeader("Connect-MCVersion", platformUtils.minecraftVersion())
                        .addHeader("Connect-JavaVersion", Metrics.JAVA_VERSION)
                        .addHeader("Connect-osName", Metrics.OS_NAME)
                        .addHeader("Connect-osArch", Metrics.OS_ARCH)
                        .addHeader("Connect-osVersion", Metrics.OS_VERSION)
                        .addHeader("Connect-coreCount", String.valueOf(Metrics.CORE_COUNT))
                        .build()))
                .build();
    }

    @Provides
    @Singleton
    @Named("watchHttpClient")
    public OkHttpClient watchOkHttpClient(
            @Named("connectHttpClient") OkHttpClient connectHttpClient
    ) {
        return connectHttpClient.newBuilder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(WATCH_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                .build();
    }

}
