package com.minekube.connect.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.api.player.Auth;
import com.minekube.connect.api.player.ConnectPlayer;
import com.minekube.connect.config.ProxyConnectConfig;
import com.minekube.connect.player.ConnectPlayerImpl;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.proxy.event.VelocityEventManager;
import com.velocitypowered.proxy.plugin.util.PluginDependencyUtils;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The ordering half of Connect's defensive login re-assert, asserted against the <b>real</b>
 * Velocity proxy - {@link VelocityEventManager} and {@link PluginDependencyUtils} - rather than
 * against a re-implementation of their rules.
 *
 * <p>What is being defended: Connect authenticates a tunneled player at its edge and forces
 * offline mode at {@code PostOrder.EARLY}. A login plugin that unconditionally forces online
 * mode at {@code PostOrder.LAST} reverts that, and the player then waits forever for an
 * encryption request nothing can answer. The re-assert must therefore land <i>after</i>
 * {@code LAST}, which is what {@link VelocityLateEventRegistrar} arranges - and it is a property
 * of Velocity's dispatcher, so only Velocity's dispatcher can prove it.
 *
 * <p>Runs in its own source set with a real (shaded) Velocity proxy jar on the classpath; see
 * {@code velocity/build.gradle.kts}.
 */
class VelocityLateEventOrderTest {
    private static final UUID CONNECT_UUID = UUID.fromString("f912bf90-8349-565f-9dc0-9891923c0cc3");

    /**
     * The whole point, end to end: Connect decides offline mode early, a {@code PostOrder.LAST}
     * plugin overrides it to online mode, and Connect's re-assert - which must run last - puts it
     * back. On the pre-fix code there is no third handler and the login hangs.
     */
    @Test
    void reassertRunsAfterAPostOrderLastHandlerAndRestoresOfflineMode() throws Exception {
        List<String> executed = new ArrayList<>();
        StubPlugin connect = new StubPlugin("connect");
        StubPlugin loginPlugin = new StubPlugin("some-login-plugin");
        VelocityEventManager eventManager =
                new VelocityEventManager(new StubPluginManager(connect, loginPlugin));

        InboundConnection connection = stubConnection();
        VelocityConnectPlayers connectPlayers = new VelocityConnectPlayers();

        // Connect's existing EARLY handler (VelocityListener#onPreLogin), unchanged by this fix.
        eventManager.register(connect, new Object() {
            @Subscribe(order = PostOrder.EARLY)
            public void onPreLogin(PreLoginEvent event) {
                executed.add("connect@EARLY");
                event.setResult(PreLoginComponentResult.forceOfflineMode());
                connectPlayers.remember(event.getConnection(), connectPlayer());
            }
        });

        // Registered BEFORE the login plugin's handler on purpose: Velocity breaks ties between
        // equal orders by registration order, so if the re-assert only reached PostOrder.LAST it
        // would run first here and lose. Passing this test therefore requires the numeric
        // priority below LAST, not merely a late PostOrder.
        VelocityLateReassertListener reassert = new VelocityLateReassertListener(
                connectPlayers, new ProxyConnectConfig(), new NoopLogger());
        reassert.register(eventManager, connect);

        // Any login plugin that forces online mode for every connection, at the latest order
        // @Subscribe can express.
        eventManager.register(loginPlugin, new Object() {
            @Subscribe(order = PostOrder.LAST)
            public void onPreLogin(PreLoginEvent event) {
                executed.add("loginPlugin@LAST");
                event.setResult(PreLoginComponentResult.forceOnlineMode());
            }
        });

        PreLoginEvent fired =
                eventManager.fire(new PreLoginEvent(connection, "ConnectSteve")).join();

        assertEquals(Arrays.asList("connect@EARLY", "loginPlugin@LAST"), executed,
                "the two pre-existing handlers must still run in their documented order");
        assertTrue(fired.getResult().isForceOfflineMode(),
                "Connect's re-assert must be the last writer of its own pre-login decision");
    }

    /**
     * A deny from any plugin survives: the re-assert is a floor under Connect's own decision, not
     * a veto over anyone else's.
     */
    @Test
    void aDenyFromALastHandlerIsNeverConvertedIntoAJoin() throws Exception {
        StubPlugin connect = new StubPlugin("connect");
        StubPlugin loginPlugin = new StubPlugin("some-login-plugin");
        VelocityEventManager eventManager =
                new VelocityEventManager(new StubPluginManager(connect, loginPlugin));

        InboundConnection connection = stubConnection();
        VelocityConnectPlayers connectPlayers = new VelocityConnectPlayers();
        connectPlayers.remember(connection, connectPlayer());

        eventManager.register(loginPlugin, new Object() {
            @Subscribe(order = PostOrder.LAST)
            public void onPreLogin(PreLoginEvent event) {
                event.setResult(PreLoginComponentResult.denied(
                        net.kyori.adventure.text.Component.text("you are banned")));
            }
        });
        new VelocityLateReassertListener(connectPlayers, new ProxyConnectConfig(), new NoopLogger())
                .register(eventManager, connect);

        PreLoginEvent fired =
                eventManager.fire(new PreLoginEvent(connection, "ConnectSteve")).join();

        assertTrue(!fired.getResult().isAllowed(), "the kick must survive the re-assert");
    }

    /**
     * The numeric-priority lever exists on any Velocity from 2024-09-16 onwards. Connect looks it
     * up reflectively on a public interface method; if that lookup ever stops resolving on a
     * modern proxy the re-assert silently degrades to a tie at {@code PostOrder.LAST}.
     */
    @Test
    void modernVelocityExposesTheNumericPriorityRegisterOverload() {
        assertNotNull(VelocityLateEventRegistrar.shortRegisterMethod());
    }

    /**
     * The fallback lever for Velocity builds older than the overload above: equal orders are
     * broken by plugin load order, which is a topological sort of the declared dependency graph.
     * Asserted against the descriptor this module actually ships, so deleting the dependency edge
     * from {@code velocity-plugin.json} fails here.
     */
    @Test
    void theShippedDescriptorMakesConnectLoadAfterTheLoginPlugin() throws Exception {
        List<PluginDependency> declared = shippedDependencies();
        assertTrue(declared.stream().allMatch(PluginDependency::isOptional),
                "every declared dependency must be optional; a hard one would stop Connect loading");
        assertTrue(declared.stream().anyMatch(d -> d.getId().equals("librelogin")),
                "velocity-plugin.json must keep the optional librelogin ordering edge");

        List<String> withLoginPlugin = sortedIds(Arrays.asList(
                description("connect", declared),
                description("librelogin", Collections.emptyList())));
        assertEquals(Arrays.asList("librelogin", "connect"), withLoginPlugin,
                "the optional dependency must make Connect register its handlers last");

        // Without the edge Connect sorts first by id and would lose the tie - the reason the edge
        // exists at all.
        List<String> withoutTheEdge = sortedIds(Arrays.asList(
                description("connect", Collections.emptyList()),
                description("librelogin", Collections.emptyList())));
        assertEquals(Arrays.asList("connect", "librelogin"), withoutTheEdge);
    }

    /** An optional dependency on a plugin that is not installed changes nothing and logs nothing. */
    @Test
    void anAbsentOptionalDependencyIsASilentNoOp() throws Exception {
        List<String> sorted = sortedIds(Arrays.asList(
                description("connect", shippedDependencies()),
                description("aaa-unrelated", Collections.emptyList())));

        assertEquals(Arrays.asList("aaa-unrelated", "connect"), sorted);
    }

    private static List<String> sortedIds(List<PluginDescription> candidates) {
        List<String> ids = new ArrayList<>();
        for (PluginDescription description : PluginDependencyUtils.sortCandidates(candidates)) {
            ids.add(description.getId());
        }
        return ids;
    }

    /** The dependencies of the {@code velocity-plugin.json} this module builds into its jar. */
    private static List<PluginDependency> shippedDependencies() throws Exception {
        List<PluginDependency> dependencies = new ArrayList<>();
        try (Reader reader = new InputStreamReader(
                VelocityLateEventOrderTest.class.getResourceAsStream("/velocity-plugin.json"),
                StandardCharsets.UTF_8)) {
            JsonObject descriptor = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray declared = descriptor.getAsJsonArray("dependencies");
            assertNotNull(declared, "velocity-plugin.json must declare dependencies");
            for (JsonElement element : declared) {
                JsonObject dependency = element.getAsJsonObject();
                dependencies.add(new PluginDependency(
                        dependency.get("id").getAsString(),
                        null,
                        dependency.get("optional").getAsBoolean()));
            }
        }
        return dependencies;
    }

    private static PluginDescription description(String id, List<PluginDependency> dependencies) {
        return new PluginDescription() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Collection<PluginDependency> getDependencies() {
                return dependencies;
            }
        };
    }

    private static ConnectPlayer connectPlayer() {
        return new ConnectPlayerImpl(
                "session-1",
                new com.minekube.connect.api.player.GameProfile(
                        "ConnectSteve", CONNECT_UUID, Collections.emptyList()),
                new Auth(false),
                "");
    }

    /**
     * A dynamic proxy rather than an implementation: {@link InboundConnection} keeps gaining
     * methods across Velocity versions, and this test only needs it as an identity.
     */
    private static InboundConnection stubConnection() {
        return (InboundConnection) Proxy.newProxyInstance(
                VelocityLateEventOrderTest.class.getClassLoader(),
                new Class<?>[]{InboundConnection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getRemoteAddress":
                            return new InetSocketAddress("127.0.0.1", 25565);
                        case "getVirtualHost":
                            return Optional.empty();
                        case "isActive":
                            return true;
                        case "getProtocolVersion":
                            return ProtocolVersion.MAXIMUM_VERSION;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        case "toString":
                            return "stub-inbound-connection";
                        default:
                            return null;
                    }
                });
    }

    private static final class StubPlugin implements PluginContainer {
        private final PluginDescription description;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        StubPlugin(String id) {
            this.description = description(id, Collections.emptyList());
        }

        @Override
        public PluginDescription getDescription() {
            return description;
        }

        @Override
        public ExecutorService getExecutorService() {
            return executor;
        }
    }

    private static final class StubPluginManager implements PluginManager {
        private final List<StubPlugin> plugins;

        StubPluginManager(StubPlugin... plugins) {
            this.plugins = Arrays.asList(plugins);
        }

        @Override
        public Optional<PluginContainer> fromInstance(Object instance) {
            return plugins.stream()
                    .filter(plugin -> plugin == instance)
                    .map(plugin -> (PluginContainer) plugin)
                    .findFirst();
        }

        @Override
        public Optional<PluginContainer> getPlugin(String id) {
            return plugins.stream()
                    .filter(plugin -> plugin.getDescription().getId().equals(id))
                    .map(plugin -> (PluginContainer) plugin)
                    .findFirst();
        }

        @Override
        public Collection<PluginContainer> getPlugins() {
            return new ArrayList<>(plugins);
        }

        @Override
        public boolean isLoaded(String id) {
            return getPlugin(id).isPresent();
        }

        @Override
        public void addToClasspath(Object plugin, Path path) {
        }
    }

    private static final class NoopLogger implements ConnectLogger {
        @Override
        public void error(String message, Object... args) {
        }

        @Override
        public void error(String message, Throwable throwable, Object... args) {
        }

        @Override
        public void warn(String message, Object... args) {
        }

        @Override
        public void info(String message, Object... args) {
        }

        @Override
        public void translatedInfo(String message, Object... args) {
        }

        @Override
        public void debug(String message, Object... args) {
        }

        @Override
        public void trace(String message, Object... args) {
        }

        @Override
        public void enableDebug() {
        }

        @Override
        public void disableDebug() {
        }

        @Override
        public boolean isDebug() {
            return false;
        }
    }
}
