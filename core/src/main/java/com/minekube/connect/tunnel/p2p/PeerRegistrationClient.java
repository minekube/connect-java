/*
 * Copyright (c) 2021-2022 Minekube. https://minekube.com
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
 * DEALINGS IN THE SOFTWARE.
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.tunnel.p2p;

import com.google.protobuf.MessageLite;
import com.minekube.connect.bedrock.BedrockPrincipalReadiness;
import io.libp2p.core.Stream;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterChallenge;
import minekube.connect.v1alpha1.ConnectLibp2P.PeerRegisterResult;
import minekube.connect.v1alpha1.WatchServiceOuterClass.ReadinessChallenge;

final class PeerRegistrationClient {
    private final PeerRegistrationHandshake handshake;
    private final ScheduledExecutorService renewExecutor;
    private final BedrockPrincipalReadiness readiness;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private volatile Stream stream;

    PeerRegistrationClient(PeerRegistrationHandshake handshake) {
        this(handshake, null);
    }

    PeerRegistrationClient(PeerRegistrationHandshake handshake, BedrockPrincipalReadiness readiness) {
        this.handshake = Objects.requireNonNull(handshake, "handshake");
        this.readiness = readiness;
        this.renewExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "connect-libp2p-registration-renew");
            thread.setDaemon(true);
            return thread;
        });
    }

    CompletableFuture<PeerRegisterResult> install(
            Stream stream,
            List<String> observedAddrs,
            long sequence,
            long nowUnixMs) {
        return install(stream, () -> observedAddrs, sequence, nowUnixMs);
    }

    CompletableFuture<PeerRegisterResult> install(
            Stream stream,
            Supplier<List<String>> observedAddrsSupplier,
            long sequence,
            long nowUnixMs) {
        this.stream = stream;
        List<String> observedAddrs = observedAddrsSupplier.get();
        CompletableFuture<PeerRegisterResult> result = new CompletableFuture<>();
        P2PFrameDecoder<PeerRegisterChallenge> challengeDecoder = new P2PFrameDecoder<>(
                PeerRegisterChallenge.parser(),
                P2PFrameCodec.MAX_CONTROL_FRAME_SIZE);
        stream.pushHandler(challengeDecoder);
        stream.pushHandler(new ChallengeHandler(
                stream,
                challengeDecoder,
                observedAddrsSupplier,
                observedAddrs,
                sequence,
                nowUnixMs,
                result));
        writeFrame(stream, handshake.init(observedAddrs));
        return result;
    }

    CompletableFuture<Void> closedFuture() {
        return closed;
    }

    void close() {
        renewExecutor.shutdownNow();
        Stream current = stream;
        if (current != null) {
            current.close();
        }
        closed.complete(null);
    }

    private void installResultHandler(
            ChannelHandlerContext ctx,
            Stream stream,
            PeerRegisterChallenge challenge,
            Supplier<List<String>> observedAddrsSupplier,
            long sequence,
            CompletableFuture<PeerRegisterResult> result) {
        P2PFrameDecoder<PeerRegisterResult> resultDecoder = new P2PFrameDecoder<>(
                PeerRegisterResult.parser(),
                P2PFrameCodec.MAX_CONTROL_FRAME_SIZE);
        ctx.pipeline().addLast(resultDecoder);
        ctx.pipeline().addLast(new ResultHandler(
                stream, resultDecoder, challenge, observedAddrsSupplier, sequence, result));
    }

    private synchronized void writeFrame(Stream stream, MessageLite message) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            P2PFrameCodec.write(out, message);
            stream.writeAndFlush(Unpooled.wrappedBuffer(out.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("encode libp2p registration frame", e);
        }
    }

    private synchronized void writeKindFrame(Stream stream, byte kind, MessageLite message) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            P2PFrameCodec.writeKindPrefixed(out, kind, message);
            stream.writeAndFlush(Unpooled.wrappedBuffer(out.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("encode kind-prefixed libp2p registration frame", e);
        }
    }

    private final class ChallengeHandler extends SimpleChannelInboundHandler<PeerRegisterChallenge> {
        private final Stream stream;
        private final ChannelHandler decoder;
        private final Supplier<List<String>> observedAddrsSupplier;
        private final List<String> observedAddrs;
        private final long sequence;
        private final long nowUnixMs;
        private final CompletableFuture<PeerRegisterResult> result;

        private ChallengeHandler(
                Stream stream,
                ChannelHandler decoder,
                Supplier<List<String>> observedAddrsSupplier,
                List<String> observedAddrs,
                long sequence,
                long nowUnixMs,
                CompletableFuture<PeerRegisterResult> result) {
            this.stream = stream;
            this.decoder = decoder;
            this.observedAddrsSupplier = observedAddrsSupplier;
            this.observedAddrs = observedAddrs;
            this.sequence = sequence;
            this.nowUnixMs = nowUnixMs;
            this.result = result;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, PeerRegisterChallenge challenge) {
            ctx.pipeline().remove(this);
            ctx.pipeline().remove(decoder);
            writeFrame(stream, handshake.commit(challenge, observedAddrs, sequence, nowUnixMs));
            installResultHandler(ctx, stream, challenge, observedAddrsSupplier, sequence, result);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            result.completeExceptionally(cause);
            closed.completeExceptionally(cause);
            stream.close();
            ctx.close();
        }
    }

    static long renewDelayMillis(PeerRegisterChallenge challenge) {
        if (challenge.getRenewIntervalMs() > 0) {
            return Math.max(1_000, challenge.getRenewIntervalMs());
        }
        if (challenge.getKvTtlMs() > 0) {
            return Math.max(1_000, challenge.getKvTtlMs() / 2);
        }
        return 22_500;
    }

    private final class ResultHandler extends SimpleChannelInboundHandler<PeerRegisterResult> {
        private final Stream stream;
        private final ChannelHandler legacyDecoder;
        private final PeerRegisterChallenge challenge;
        private final Supplier<List<String>> observedAddrsSupplier;
        private final AtomicLong sequence;
        private final CompletableFuture<PeerRegisterResult> result;
        private volatile ScheduledFuture<?> ackTimeout;
        private volatile boolean offerAttempted;
        private volatile boolean framed;
        private volatile boolean awaitingResult = true;

        private ResultHandler(
                Stream stream,
                ChannelHandler legacyDecoder,
                PeerRegisterChallenge challenge,
                Supplier<List<String>> observedAddrsSupplier,
                long sequence,
                CompletableFuture<PeerRegisterResult> result) {
            this.stream = stream;
            this.legacyDecoder = legacyDecoder;
            this.challenge = challenge;
            this.observedAddrsSupplier = observedAddrsSupplier;
            this.sequence = new AtomicLong(sequence);
            this.result = result;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, PeerRegisterResult msg) {
            handleResult(ctx, msg);
        }

        private void handleResult(ChannelHandlerContext ctx, PeerRegisterResult msg) {
            if (!awaitingResult) {
                failRegistration(new IllegalArgumentException(
                        "unexpected duplicate libp2p registration result"));
                ctx.close();
                return;
            }
            awaitingResult = false;
            cancelAckTimeout();
            if (msg.hasModeResult()
                    && msg.getModeResult().getVersion() == 2
                    && !msg.getModeResult().getAccepted()
                    && framed) {
                framed = false;
                failRegistration(new IllegalStateException("libp2p registration framing rejected"));
                return;
            }
            result.complete(msg);
            if (!framed && offerAttempted && msg.hasModeResult()
                    && msg.getModeResult().getVersion() == 2
                    && msg.getModeResult().getAccepted()) {
                framed = true;
                ctx.pipeline().addLast(new KindFrameDecoder());
                ctx.pipeline().addLast(new KindFrameHandler(this));
                ctx.pipeline().remove(this);
                ctx.pipeline().remove(legacyDecoder);
            }
            scheduleRenew();
        }

        private void scheduleRenew() {
            renewExecutor.schedule(() -> {
                if (!stream.closeFuture().isDone()) {
                    try {
                        boolean offer = !framed && !offerAttempted
                                && readiness != null && readiness.isReady();
                        MessageLite commit = handshake.commit(
                                challenge,
                                observedAddrsSupplier.get(),
                                sequence.incrementAndGet(),
                                System.currentTimeMillis(),
                                offer,
                                framed);
                        awaitingResult = true;
                        if (framed) {
                            writeKindFrame(stream, P2PFrameCodec.RENEWAL_COMMIT, commit);
                        } else {
                            writeFrame(stream, commit);
                            if (offer) offerAttempted = true;
                        }
                        scheduleAckTimeout();
                    } catch (RuntimeException e) {
                        failRegistration(e);
                    }
                }
            }, renewDelayMillis(challenge), TimeUnit.MILLISECONDS);
        }

        private void scheduleAckTimeout() {
            cancelAckTimeout();
            ackTimeout = renewExecutor.schedule(
                    () -> failRegistration(new TimeoutException("libp2p registration renew ack timed out")),
                    renewAckTimeoutMillis(challenge),
                    TimeUnit.MILLISECONDS);
        }

        private void cancelAckTimeout() {
            ScheduledFuture<?> timeout = ackTimeout;
            if (timeout != null) {
                timeout.cancel(false);
                ackTimeout = null;
            }
        }

        private void failRegistration(Throwable cause) {
            cancelAckTimeout();
            result.completeExceptionally(cause);
            closed.completeExceptionally(cause);
            stream.close();
            renewExecutor.shutdownNow();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            failRegistration(cause);
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cancelAckTimeout();
            renewExecutor.shutdownNow();
            closed.complete(null);
            super.channelInactive(ctx);
        }
    }

    private final class KindFrameHandler
            extends SimpleChannelInboundHandler<P2PFrameCodec.KindPrefixedFrame> {
        private final ResultHandler registration;

        private KindFrameHandler(ResultHandler registration) {
            this.registration = registration;
        }

        @Override
        protected void channelRead0(
                ChannelHandlerContext ctx, P2PFrameCodec.KindPrefixedFrame frame) throws Exception {
            if (frame.kind() == P2PFrameCodec.RENEWAL_RESULT) {
                registration.handleResult(ctx, frame.parse(PeerRegisterResult.parser()));
                return;
            }
            if (frame.kind() == P2PFrameCodec.READINESS_CHALLENGE && readiness != null) {
                ReadinessChallenge challenge = frame.parse(ReadinessChallenge.parser());
                writeKindFrame(stream, P2PFrameCodec.READINESS_ATTESTATION,
                        readiness.attest(challenge, BedrockPrincipalReadiness.Transport.LIBP2P));
                return;
            }
            registration.failRegistration(new IllegalArgumentException(
                    "unexpected kind-prefixed registration frame"));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            registration.failRegistration(cause);
            ctx.close();
        }
    }

    private static final class KindFrameDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            in.markReaderIndex();
            long length = 0;
            int shift = 0;
            boolean complete = false;
            for (int index = 0; index < 10; index++) {
                if (!in.isReadable()) {
                    in.resetReaderIndex();
                    return;
                }
                int value = in.readUnsignedByte();
                if (index == 9 && value > 1) {
                    throw new IllegalArgumentException("kind-prefixed frame length overflow");
                }
                length |= (long) (value & 0x7f) << shift;
                if ((value & 0x80) == 0) {
                    complete = true;
                    break;
                }
                shift += 7;
            }
            if (!complete || length < 1 || length > P2PFrameCodec.MAX_KIND_PREFIXED_FRAME_SIZE) {
                throw new IllegalArgumentException("invalid kind-prefixed frame length");
            }
            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }
            byte kind = in.readByte();
            if (kind < P2PFrameCodec.RENEWAL_COMMIT
                    || kind > P2PFrameCodec.READINESS_ATTESTATION) {
                throw new IllegalArgumentException("unknown kind-prefixed frame kind");
            }
            byte[] payload = new byte[(int) length - 1];
            in.readBytes(payload);
            out.add(new P2PFrameCodec.KindPrefixedFrame(kind, payload));
        }
    }

    static long renewAckTimeoutMillis(PeerRegisterChallenge challenge) {
        long renewDelay = renewDelayMillis(challenge);
        if (challenge.getKvTtlMs() > 0) {
            return Math.max(1_000, Math.min(renewDelay, challenge.getKvTtlMs() / 2));
        }
        return Math.max(1_000, renewDelay);
    }
}
