package com.minekube.connect.addon.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.api.logger.ConnectLogger;
import com.minekube.connect.util.MessageFormatter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import org.junit.jupiter.api.Test;

/**
 * Guard for the connector-side attribution of a tunneled login that never completes.
 *
 * <p>Measured shape of the defect (Paper 26.3 + connect-spigot 0.15.15 + a PacketEvents listener
 * that cancels {@code LOGIN_START}): the handshake reaches the connector's pipeline position,
 * {@code LOGIN_START} never does, so the connector's fake offline-login cycle never runs and the
 * only line anywhere is the server's cause-blind {@code Took too long to log in}. These tests drive
 * the real {@link SpigotLoginStallWatchdog} through an {@link EmbeddedChannel} at its production
 * position - with a handler at {@code connect_data_handler} that consumes {@code LOGIN_START}
 * exactly like {@link SpigotDataHandler} does - so the position, the window and the wording are all
 * under test, and a healthy login cannot be reported as a stall.
 */
class SpigotLoginStallWatchdogTest {
    private static final String PACKET_HANDLER = "packet_handler";
    private static final String PLAYER = "coconut";
    private static final String SESSION = "8b1f2c34-5d6e-4f70-891a-b2c3d4e5f607";
    private static final String ENDPOINT = "hce2e1535984129";

    private final RecordingLogger logger = new RecordingLogger();

    @Test
    void watchesEveryLoginStartTheDataHandlerConsumes() {
        Fixture fixture = new Fixture(logger);
        List<String> names = fixture.channel.pipeline().names();
        assertTrue(
                names.indexOf(SpigotLoginStallWatchdog.HANDLER_NAME)
                        < names.indexOf(SpigotDataHandler.HANDLER_NAME),
                "the watchdog must observe packets upstream of the data handler: " + names);

        fixture.writeHandshake();
        fixture.writeLoginStart();

        assertEquals(2, fixture.reachedDataHandler.size());
        assertEquals(Collections.singletonList(fixture.handshake), fixture.reachedPacketHandler,
                "the data handler consumes LOGIN_START, as the real one does");
        assertTrue(logger.warnings.isEmpty(), logger.warnings.toString());
    }

    @Test
    void namesTheSessionWhenNoLoginStartReachesTheConnector() {
        Fixture fixture = new Fixture(logger);
        fixture.writeHandshake();

        assertTrue(logger.warnings.isEmpty(), "no per-packet noise before the window elapses");

        fixture.elapseStallWindow();

        assertEquals(1, logger.warnings.size(), logger.warnings.toString());
        String line = logger.warnings.get(0);
        assertTrue(line.contains("no LOGIN_START reached the connector within"), line);
        assertTrue(line.contains(SpigotLoginStallWatchdog.LOGIN_START_WINDOW_MILLIS + " ms"), line);
        assertTrue(line.contains("player=" + PLAYER), line);
        assertTrue(line.contains("session=" + SESSION), line);
        assertTrue(line.contains("endpoint=" + ENDPOINT), line);
        assertTrue(line.contains("packets=1"), line);
        assertTrue(line.contains("login plugin"), line);
        assertTrue(line.contains("connect-player"), line);
        assertTrue(line.contains("passthrough"), line);
    }

    @Test
    void namesAStalledSessionOnlyOnce() {
        Fixture fixture = new Fixture(logger);
        fixture.writeHandshake();
        fixture.elapseStallWindow();
        assertEquals(1, logger.warnings.size(), logger.warnings.toString());

        // A repeated handshake re-arms the window: the session still must not be named twice.
        fixture.writeHandshake();
        fixture.elapseStallWindow();

        assertEquals(1, logger.warnings.size(), logger.warnings.toString());
    }

    @Test
    void staysQuietWhenTheLoginStartArrives() {
        Fixture fixture = new Fixture(logger);
        fixture.writeHandshake();
        fixture.writeLoginStart();

        fixture.elapseStallWindow();

        assertTrue(logger.warnings.isEmpty(), logger.warnings.toString());
    }

    @Test
    void staysQuietWithoutAHandshake() {
        Fixture fixture = new Fixture(logger);
        fixture.writeLoginStart();

        fixture.elapseStallWindow();

        assertTrue(logger.warnings.isEmpty(), logger.warnings.toString());
    }

    @Test
    void staysQuietWhenTheSessionEndsBeforeTheWindow() {
        Fixture fixture = new Fixture(logger);
        fixture.writeHandshake();
        fixture.channel.close();

        fixture.elapseStallWindow();

        assertTrue(logger.warnings.isEmpty(), logger.warnings.toString());
    }

    /**
     * The connector's pipeline tail as production builds it: a {@code packet_handler} sink, a data
     * handler at {@code connect_data_handler} that consumes {@code LOGIN_START} (as the real
     * {@link SpigotDataHandler} does), and the real watchdog installed through its production
     * installation call, which anchors it upstream of that data handler.
     */
    private static final class Fixture {
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final Object handshake = new ClientIntentionPacket(777, "play.example.net", 25565);
        private final List<Object> reachedDataHandler = new ArrayList<>();
        private final List<Object> reachedPacketHandler = new ArrayList<>();

        private Fixture(ConnectLogger logger) {
            channel.pipeline().addLast(PACKET_HANDLER, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    reachedPacketHandler.add(msg);
                    ctx.fireChannelRead(msg);
                }
            });
            channel.pipeline().addBefore(PACKET_HANDLER, SpigotDataHandler.HANDLER_NAME,
                    new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg)
                                throws Exception {
                            reachedDataHandler.add(msg);
                            if (msg instanceof ServerboundHelloPacket) {
                                return;
                            }
                            super.channelRead(ctx, msg);
                        }
                    });
            SpigotLoginStallWatchdog.install(channel.pipeline(), new SpigotLoginStallWatchdog(
                    logger, PLAYER, SESSION, ENDPOINT));
        }

        private void writeHandshake() {
            channel.writeInbound(handshake);
        }

        private void writeLoginStart() {
            channel.writeInbound(new ServerboundHelloPacket(PLAYER, UUID.randomUUID()));
        }

        private void elapseStallWindow() {
            channel.advanceTimeBy(
                    SpigotLoginStallWatchdog.LOGIN_START_WINDOW_MILLIS + 1, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();
        }
    }

    /**
     * Records the rendered warning lines, using the production formatter so the assertions are about
     * what an operator would read.
     */
    private static final class RecordingLogger implements ConnectLogger {
        private final List<String> warnings = new ArrayList<>();

        @Override
        public void warn(String message, Object... args) {
            warnings.add(MessageFormatter.format(message, args));
        }

        @Override
        public void error(String message, Object... args) {
        }

        @Override
        public void error(String message, Throwable throwable, Object... args) {
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
