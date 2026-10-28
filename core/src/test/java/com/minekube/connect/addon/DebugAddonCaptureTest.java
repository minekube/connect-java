package com.minekube.connect.addon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.api.logger.ConnectLogger;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Guards the operator-facing contract of {@code debug: true}: a debug dump must show <em>both</em>
 * directions of the tunneled connection, and a direction that cannot be recorded must say so loudly.
 *
 * <p>Defect this reproduces (Paper 26.3, connect-spot 0.15.17, case t_dc27dadd §6): the outbound
 * recorder was installed only in front of the configured {@code encoder} name. Paper 26.3 names the
 * outbound packet encoder {@code outbound_config} on the injected, configuration-capable connection
 * ({@code Connection.outboundHandlerName}), so the recorder was never installed, the skip was logged at
 * debug level, and a full successful join produced 25 {@code Player -> Spigot} lines and zero
 * {@code Spigot -> Player} lines. Three support rounds read that as "Paper sent nothing".
 *
 * <p>Also guarded: the recorders must log at WARN when they cannot sit in front of an encoder, and each
 * direction needs its own packet budget - the old shared counter let the direction that spoke first
 * consume {@code MAX_DEBUG_PACKET_COUNT} and silence the other one entirely.
 */
class DebugAddonCaptureTest {
    private static final String PLATFORM = "Spigot";
    private static final String CONFIGURED_ENCODER = "encoder";
    private static final String CONFIG_STATE_ENCODER = "outbound_config";
    private static final String DECODER = "decoder";

    private final RecordingLogger logger = new RecordingLogger();

    @Test
    void recordsTheOutboundDirectionWhenThePlatformNamesItsEncoderDifferently() {
        // The pipeline shape measured on Paper 26.3 for the injected connection.
        EmbeddedChannel channel = configStatePipeline();
        try {
            DebugAddon.installRecorders(
                    channel, PLATFORM, false, CONFIGURED_ENCODER, DECODER, logger);

            List<String> names = channel.pipeline().names();
            assertEquals(
                    names.indexOf(CONFIG_STATE_ENCODER) - 1,
                    names.indexOf(DebugAddon.OUT_HANDLER_NAME),
                    "the recorder must sit in front of the encoder the platform actually installed: "
                            + names);

            channel.writeOutbound(new FakePacket("login_success"));
            channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x01, 0x00}));

            assertEquals(
                    1,
                    logger.lines("Spigot -> Player "),
                    "outbound entries must be recorded: " + String.join("\n", logger.all()));
            assertEquals(1, logger.lines("Player -> Spigot "), String.join("\n", logger.all()));

            String warning = String.join("\n", logger.warnings);
            assertTrue(warning.contains(CONFIG_STATE_ENCODER), warning);
            assertTrue(warning.contains(CONFIGURED_ENCODER), warning);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void recordsInFrontOfTheConfiguredEncoderWithoutAWarning() {
        EmbeddedChannel channel = configStatePipeline();
        try {
            channel.pipeline().addBefore("packet_handler", CONFIGURED_ENCODER, new PassThrough());
            DebugAddon.installRecorders(
                    channel, PLATFORM, false, CONFIGURED_ENCODER, DECODER, logger);

            List<String> names = channel.pipeline().names();
            assertEquals(
                    names.indexOf(CONFIGURED_ENCODER) - 1,
                    names.indexOf(DebugAddon.OUT_HANDLER_NAME),
                    "the recorder still sits directly in front of the configured encoder: " + names);
            assertTrue(logger.warnings.isEmpty(), String.join("\n", logger.all()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void recordsAtTheTailAndWarnsWhenThePipelineHasNoEncoderAtAll() {
        EmbeddedChannel channel = new EmbeddedChannel(new PassThrough());
        channel.pipeline().addLast(DECODER, new PassThrough());
        channel.pipeline().addLast("packet_handler", new PassThrough());
        try {
            DebugAddon.installRecorders(
                    channel, PLATFORM, false, CONFIGURED_ENCODER, DECODER, logger);

            assertNotNull(
                    channel.pipeline().get(DebugAddon.OUT_HANDLER_NAME),
                    "the outbound recorder must exist even without any encoder: "
                            + channel.pipeline().names());
            assertTrue(
                    channel.pipeline().names().indexOf(DebugAddon.OUT_HANDLER_NAME)
                            > channel.pipeline().names().indexOf("packet_handler"),
                    "without an encoder the recorder belongs at the tail: " + channel.pipeline().names());

            channel.writeOutbound(new FakePacket("login_success"));
            assertEquals(1, logger.lines("Spigot -> Player "), String.join("\n", logger.all()));
            assertTrue(
                    logger.matching("Spigot -> Player ").get(0).contains("(pre-encoding)"),
                    "a pre-encoding capture must say so instead of recording nothing: "
                            + String.join("\n", logger.all()));

            String warning = String.join("\n", logger.warnings);
            assertTrue(warning.contains("tail"), warning);
            assertTrue(warning.contains("no packet encoder"), warning);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void saysOutLoudWhenTheInboundDirectionCannotBeRecorded() {
        EmbeddedChannel channel = new EmbeddedChannel(new PassThrough());
        channel.pipeline().addLast("packet_handler", new PassThrough());
        try {
            DebugAddon.installRecorders(
                    channel, PLATFORM, false, CONFIGURED_ENCODER, DECODER, logger);

            assertNull(channel.pipeline().get(DebugAddon.IN_HANDLER_NAME));
            String warning = String.join("\n", logger.warnings);
            assertTrue(warning.contains(DECODER), warning);
            assertTrue(warning.contains("one-sided"), warning);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void givesEachDirectionItsOwnPacketBudget() {
        EmbeddedChannel channel = configStatePipeline();
        try {
            DebugAddon.installRecorders(
                    channel, PLATFORM, false, CONFIGURED_ENCODER, DECODER, logger);

            // The direction that speaks first must not exhaust the budget of the other one.
            for (int i = 0; i < 30; i++) {
                channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{(byte) i}));
            }
            for (int i = 0; i < 30; i++) {
                channel.writeOutbound(new FakePacket("config-" + i));
            }

            assertEquals(25, logger.lines("Player -> Spigot "), "inbound budget");
            assertEquals(25, logger.lines("Spigot -> Player "), "outbound budget");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** Mirrors Paper 26.3: decoder, framing prepender, then the config-state packet encoder. */
    private static EmbeddedChannel configStatePipeline() {
        EmbeddedChannel channel = new EmbeddedChannel(new PassThrough());
        channel.pipeline().addLast("splitter", new PassThrough());
        channel.pipeline().addLast(DECODER, new PassThrough());
        channel.pipeline().addLast("prepender", new PassThrough());
        channel.pipeline().addLast(CONFIG_STATE_ENCODER, new FakePacketEncoder());
        channel.pipeline().addLast("hackfix", new PassThrough());
        channel.pipeline().addLast("packet_handler", new PassThrough());
        return channel;
    }

    /** Stands in for a packet on the wire, before encoding. */
    private static final class FakePacket {
        private final String name;

        private FakePacket(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return "FakePacket(" + name + ")";
        }
    }

    /** Encodes a packet into the ByteBuf the recorder must see. */
    private static final class FakePacketEncoder extends MessageToMessageEncoder<Object> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
            out.add(Unpooled.wrappedBuffer(new byte[]{0x02, 0x21, 0x30}));
        }
    }

    private static final class PassThrough extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.fireChannelRead(msg);
        }
    }

    private static final class RecordingLogger implements ConnectLogger {
        private final List<String> infos = new CopyOnWriteArrayList<>();
        private final List<String> warnings = new CopyOnWriteArrayList<>();
        private final List<String> debugLines = new CopyOnWriteArrayList<>();

        @Override
        public void error(String message, Object... args) {
            // not asserted
        }

        @Override
        public void error(String message, Throwable throwable, Object... args) {
            // not asserted
        }

        @Override
        public void warn(String message, Object... args) {
            warnings.add(format(message, args));
        }

        @Override
        public void info(String message, Object... args) {
            infos.add(format(message, args));
        }

        @Override
        public void translatedInfo(String message, Object... args) {
            infos.add(format(message, args));
        }

        @Override
        public void debug(String message, Object... args) {
            debugLines.add(format(message, args));
        }

        @Override
        public void trace(String message, Object... args) {
            debugLines.add(format(message, args));
        }

        @Override
        public void enableDebug() {
        }

        @Override
        public void disableDebug() {
        }

        @Override
        public boolean isDebug() {
            return true;
        }

        int lines(String prefix) {
            return matching(prefix).size();
        }

        List<String> matching(String prefix) {
            List<String> matched = new ArrayList<>();
            for (String line : infos) {
                if (line.startsWith(prefix)) {
                    matched.add(line);
                }
            }
            return matched;
        }

        List<String> all() {
            List<String> all = new ArrayList<>(warnings);
            all.addAll(infos);
            all.addAll(debugLines);
            return all;
        }

        private static String format(String message, Object... args) {
            if (args == null || args.length == 0) {
                return message;
            }
            StringBuilder out = new StringBuilder();
            int arg = 0;
            int from = 0;
            int at;
            while ((at = message.indexOf("{}", from)) != -1) {
                out.append(message, from, at);
                out.append(arg < args.length ? String.valueOf(args[arg]) : "{}");
                arg++;
                from = at + 2;
            }
            out.append(message.substring(from));
            return out.toString();
        }
    }
}
