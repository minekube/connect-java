package com.minekube.connect.inject.spigot;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.minekube.connect.api.logger.ConnectLogger;
import com.viaversion.viaversion.bukkit.handlers.BukkitChannelInitializer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the ViaVersion 5.x legacy-injector startup failure on plain
 * Spigot/CraftBukkit.
 *
 * <p><b>Root cause.</b> ViaVersion only wraps the server's child handler on servers <em>without</em>
 * {@code io.papermc.paper.network.ChannelInitializeListener} - i.e. plain Spigot/CraftBukkit, since
 * on Paper {@code BukkitViaInjector} registers a Paper channel-initialize listener instead. On that
 * legacy path {@code SpigotInjector#getChildHandler} has to unwrap Via's {@link
 * BukkitChannelInitializer} so Connect's local channel does not double-inject Via's handlers. It did
 * so with {@code getOriginal()}, which Via 5.0 renamed to {@code original()} (pulled up into {@code
 * com.viaversion.viaversion.platform.WrappedChannelInitializer}). Connect compiled against Via
 * 4.0.0, so this built fine and threw {@link NoSuchMethodError} at runtime. That is an {@link Error},
 * not an {@link Exception}, so it escaped the {@code catch} in {@code ConnectPlatform.enable()},
 * escaped {@code onEnable()} and Bukkit disabled the plugin: Connect never bound its local channel
 * and <em>no</em> Connect player could join. Latent since Via 5.0 (2024).
 *
 * <p><b>Why this test is meaningful.</b> It runs against a real ViaVersion 5.x on the test classpath
 * (see {@code spigot/build.gradle.kts}) and executes the real private {@code getChildHandler}, so it
 * fails with the original {@code NoSuchMethodError} on the pre-fix code and passes on the fix.
 * {@link #testClasspathViaIsThePostRenameApi()} keeps it from going vacuously green if the Via test
 * dependency is ever downgraded to a pre-rename 4.x.
 */
class SpigotInjectorViaLegacyPathTest {

    /**
     * The server's own child initializer, which is what Connect must end up initializing its local
     * channel with. {@code LegacyViaInjector} replaces it in the acceptor with a
     * {@link BukkitChannelInitializer} wrapping it.
     */
    private final ChannelInitializer<Channel> serverInitializer = new ChannelInitializer<Channel>() {
        @Override
        protected void initChannel(Channel ch) {
        }
    };

    @Test
    void unwrapsViaVersionLegacyChannelInitializer() throws Exception {
        ChannelInitializer<Channel> viaInitializer =
                new BukkitChannelInitializer(serverInitializer);
        SpigotInjector injector = new SpigotInjector(mock(ConnectLogger.class), true);

        ChannelInitializer<Channel> resolved =
                getChildHandler(injector, listeningChannelWith(viaInitializer));

        assertSame(serverInitializer, resolved,
                "getChildHandler must unwrap ViaVersion's BukkitChannelInitializer down to the "
                        + "server's own initializer, otherwise Via's handlers get double-injected "
                        + "into Connect's local channel");
    }

    /**
     * Control, mirroring the pre-fix reproduction: with {@code isViaVersion == false} the unwrap
     * branch is skipped entirely, so a failure in the test above is attributable to the unwrap and
     * not to this harness.
     */
    @Test
    void keepsViaInitializerWhenViaVersionIsNotDetected() throws Exception {
        ChannelInitializer<Channel> viaInitializer =
                new BukkitChannelInitializer(serverInitializer);
        SpigotInjector injector = new SpigotInjector(mock(ConnectLogger.class), false);

        ChannelInitializer<Channel> resolved =
                getChildHandler(injector, listeningChannelWith(viaInitializer));

        assertSame(viaInitializer, resolved);
    }

    /**
     * A non-Via child handler is returned untouched even when ViaVersion is detected - Via's
     * Paper-injector path (and every non-wrapped setup) hits this.
     */
    @Test
    void returnsPlainChildHandlerUntouched() throws Exception {
        SpigotInjector injector = new SpigotInjector(mock(ConnectLogger.class), true);

        ChannelInitializer<Channel> resolved =
                getChildHandler(injector, listeningChannelWith(serverInitializer));

        assertSame(serverInitializer, resolved);
    }

    /**
     * The next rename must degrade, not detonate: when no known accessor exists the wrapper is
     * returned as-is and a warning is logged, so Connect still enables (with Via's handlers
     * double-injected) instead of throwing an {@link Error} out of {@code onEnable()}.
     */
    @Test
    void unknownViaInitializerApiDegradesToSkippingTheUnwrap() throws Exception {
        ConnectLogger logger = mock(ConnectLogger.class);
        SpigotInjector injector = new SpigotInjector(logger, true);
        Method unwrap = SpigotInjector.class.getDeclaredMethod(
                "unwrapViaInitializer", ChannelInitializer.class);
        unwrap.setAccessible(true);

        assertSame(serverInitializer, unwrap.invoke(injector, serverInitializer));
        verify(logger).warn(contains("Could not unwrap ViaVersion's channel initializer"));
    }

    /**
     * Signature-level guard on the test fixture itself: the ViaVersion on the test classpath must be
     * a post-rename 5.x, i.e. expose {@code original()} and no longer {@code getOriginal()}.
     * Downgrading the test dependency to Via 4.x would make
     * {@link #unwrapsViaVersionLegacyChannelInitializer()} pass for the wrong reason.
     */
    @Test
    void testClasspathViaIsThePostRenameApi() throws Exception {
        assertSame(ChannelInitializer.class,
                BukkitChannelInitializer.class.getMethod("original").getReturnType(),
                "expected ViaVersion 5.x, whose channel initializer exposes original()");
        assertThrows(NoSuchMethodException.class,
                () -> BukkitChannelInitializer.class.getMethod("getOriginal"),
                "expected ViaVersion 5.x, which removed getOriginal() - the whole point of this "
                        + "regression test");
    }

    /**
     * Fakes the listening channel {@code getChildHandler} walks: a pipeline holding a single handler
     * that declares a {@code childHandler} field, like Netty's {@code ServerBootstrapAcceptor}.
     */
    private static ChannelFuture listeningChannelWith(ChannelInitializer<Channel> childHandler) {
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        when(pipeline.names()).thenReturn(Collections.singletonList("acceptor"));
        when(pipeline.get("acceptor")).thenReturn(new AcceptorStub(childHandler));

        Channel channel = mock(Channel.class);
        when(channel.pipeline()).thenReturn(pipeline);

        ChannelFuture future = mock(ChannelFuture.class);
        when(future.channel()).thenReturn(channel);
        return future;
    }

    @SuppressWarnings("unchecked")
    private static ChannelInitializer<Channel> getChildHandler(
            SpigotInjector injector, ChannelFuture listeningChannel) throws Exception {
        Method getChildHandler =
                SpigotInjector.class.getDeclaredMethod("getChildHandler", ChannelFuture.class);
        getChildHandler.setAccessible(true);
        try {
            return (ChannelInitializer<Channel>) getChildHandler.invoke(injector, listeningChannel);
        } catch (InvocationTargetException e) {
            // Pre-fix this is NoSuchMethodError: BukkitChannelInitializer.getOriginal(). Report it
            // as the failure it is instead of an opaque InvocationTargetException.
            throw new AssertionError("getChildHandler threw " + e.getCause(), e.getCause());
        }
    }

    /** Stand-in for Netty's package-private {@code ServerBootstrapAcceptor}. */
    private static final class AcceptorStub extends ChannelInboundHandlerAdapter {
        @SuppressWarnings("unused") // read reflectively by SpigotInjector#getChildHandler
        private final ChannelInitializer<Channel> childHandler;

        AcceptorStub(ChannelInitializer<Channel> childHandler) {
            this.childHandler = childHandler;
        }
    }
}
