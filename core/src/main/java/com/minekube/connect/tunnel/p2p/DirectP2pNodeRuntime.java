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
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.minekube.connect.tunnel.p2p;

import com.minekube.connect.tunnel.p2p.impl.Libp2pTunnelTransportRuntime;
import io.libp2p.core.Connection;
import io.libp2p.core.Host;
import io.libp2p.core.PeerId;
import io.libp2p.core.PeerInfo;
import io.libp2p.core.Stream;
import io.libp2p.core.StreamPromise;
import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.KeyType;
import io.libp2p.core.crypto.PrivKey;
import io.libp2p.core.multiformats.Multiaddr;
import io.libp2p.core.multiformats.MultiaddrComponent;
import io.libp2p.core.multiformats.Protocol;
import io.libp2p.core.multistream.StrictProtocolBinding;
import io.libp2p.discovery.mdns.JmDNS;
import io.libp2p.discovery.mdns.ServiceInfo;
import io.libp2p.discovery.mdns.impl.DNSRecord;
import io.libp2p.protocol.ProtocolHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Pair;

/**
 * Child-loaded implementation. No method signature may expose libp2p, Netty,
 * Kotlin, or kotlinx types to {@link DirectP2pNode}.
 */
final class DirectP2pNodeRuntime {
    static final String TUNNEL_PROTOCOL_ID = "/minekube/connect/share/tunnel/1.0.0";
    static final String INFO_PROTOCOL_ID = "/minekube/connect/share/info/1.0.0";
    private static final int PREFACE_MAGIC = 0x43534831; // CSH1
    private static final int WIRE_VERSION = 1;
    private static final int MAX_PREFACE_SIZE = 4096;
    private static final int MAX_INFO_SIZE = 32 * 1024;
    private static final String MDNS_SERVICE = "_minekube-connect-share._tcp.local.";
    private static final int MDNS_QUERY_INTERVAL_SECONDS = 5;
    private static final long START_TIMEOUT_SECONDS = 10;
    private static final byte[] ED25519_X509_PREFIX = new byte[] {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x03, 0x21, 0x00
    };

    private final PrivKey privateKey;
    private final List<ProxyRuntime> proxies = new CopyOnWriteArrayList<>();
    private final java.util.Set<String> discoveredInvitations =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final java.util.Set<String> mdnsInspections =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Host host;
    private DirectP2pHostConfig hostConfig;
    private DirectP2pHostHandler hostHandler;
    private volatile String invitation;
    private JmDNS discovery;
    private DirectP2pDiscoveryListener discoveryListener;
    private boolean started;
    private boolean closed;

    DirectP2pNodeRuntime() {
        Pair<PrivKey, ?> pair = KeyKt.generateKeyPair(KeyType.ED25519);
        this.privateKey = pair.getFirst();
    }

    DirectP2pNodeRuntime(Path identityFile) throws IOException {
        this.privateKey = EndpointPeerIdentity
                .loadOrCreate(Objects.requireNonNull(identityFile, "identityFile"))
                .privateKey();
    }

    synchronized String peerId() {
        ensureOpen();
        return PeerId.fromPubKey(privateKey.publicKey()).toBase58();
    }

    synchronized byte[] publicKey() {
        ensureOpen();
        return x509PublicKey(privateKey.publicKey().raw());
    }

    synchronized DirectP2pHostInfo startHost(
            DirectP2pHostConfig config,
            DirectP2pHostHandler handler) {
        ensureOpen();
        if (hostConfig != null) {
            if (!hostConfig.shareId().equals(config.shareId())
                    || !hostConfig.capability().equals(config.capability())) {
                throw new IllegalStateException(
                        "Connect Share direct host identity cannot change while running");
            }
        }
        hostConfig = Objects.requireNonNull(config, "config");
        hostHandler = Objects.requireNonNull(handler, "handler");
        if (host == null) {
            host = Libp2pTunnelTransportRuntime.createHost(
                    privateKey,
                    "/ip4/0.0.0.0/tcp/0");
            installProtocols(host);
            startHostIfNeeded();
        } else if (host.listenAddresses().isEmpty()) {
            await(
                    host.getNetwork().listen(
                            Multiaddr.fromString("/ip4/0.0.0.0/tcp/0")),
                    START_TIMEOUT_SECONDS,
                    "listen for Connect Share direct hosting");
        }

        int port = listenTcpPort(host);
        String peerId = host.getPeerId().toBase58();
        List<String> lanAddresses = addresses(port, peerId, false);
        List<String> internetAddresses = config.internetDirectEnabled()
                ? addresses(port, peerId, true)
                : Collections.emptyList();
        if (lanAddresses.isEmpty()) {
            lanAddresses = Collections.singletonList(
                    "/ip4/127.0.0.1/tcp/" + port + "/p2p/" + peerId);
        }
        return new DirectP2pHostInfo(
                peerId,
                x509PublicKey(privateKey.publicKey().raw()),
                lanAddresses,
                internetAddresses);
    }

    synchronized byte[] sign(byte[] payload) {
        ensureOpen();
        return privateKey.sign(Arrays.copyOf(payload, payload.length));
    }

    synchronized void publish(String invitation) {
        ensureOpen();
        if (hostConfig == null || host == null) {
            throw new IllegalStateException("Connect Share direct host is not started");
        }
        this.invitation = requireInvitation(invitation);
        startMdns();
    }

    synchronized DirectP2pDiscoveredShare inspect(
            String address,
            Duration timeout) {
        ensureOpen();
        DirectP2pNode.rejectRelayAddress(address);
        ensureGuestHost(false);
        Multiaddr multiaddr = Multiaddr.fromString(address);
        PeerId peerId = multiaddr.getPeerId();
        if (peerId == null) {
            throw new IllegalArgumentException(
                    "direct address must include /p2p/<peer-id>");
        }
        Connection connection = await(
                host.getNetwork().connect(peerId, multiaddr),
                timeout,
                "dial the Connect Share metadata service");
        StreamPromise<InfoController> promise = host.newStream(
                Collections.singletonList(INFO_PROTOCOL_ID),
                connection);
        InfoController controller = await(
                promise.getController(),
                timeout,
                "negotiate the Connect Share metadata protocol");
        InfoResponse response = await(
                controller.response,
                timeout,
                "read Connect Share metadata");
        return new DirectP2pDiscoveredShare(
                response.displayName,
                peerId.toBase58(),
                address,
                response.invitation);
    }

    synchronized void startDiscovery(DirectP2pDiscoveryListener listener) {
        ensureOpen();
        if (discoveryListener != null) {
            throw new IllegalStateException("Connect Share LAN discovery is already started");
        }
        discoveryListener = Objects.requireNonNull(listener, "listener");
        ensureGuestHost(true);
        startMdns();
    }

    synchronized DirectP2pProxy openProxy(
            String address,
            String shareId,
            String capability,
            DirectP2pAuthMode authMode,
            Duration timeout) {
        ensureOpen();
        DirectP2pNode.rejectRelayAddress(address);
        Objects.requireNonNull(shareId, "shareId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(authMode, "authMode");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("direct dial timeout must be positive");
        }

        ensureGuestHost(false);
        ProxyRuntime proxy = null;
        try {
            proxy = new ProxyRuntime(
                    host,
                    address,
                    new DirectPreface(shareId, capability, authMode),
                    timeout);
            proxy.start();
            proxies.add(proxy);
            ProxyRuntime active = proxy;
            return new DirectP2pProxy(proxy.localAddress(), () -> {
                active.close();
                proxies.remove(active);
            });
        } catch (Exception e) {
            if (proxy != null) {
                proxy.close();
            }
            throw e instanceof RuntimeException
                    ? (RuntimeException) e
                    : new IllegalStateException(
                            "Could not bind the direct Minecraft proxy",
                            e);
        }
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        for (ProxyRuntime proxy : proxies) {
            proxy.close();
        }
        proxies.clear();
        if (host != null && started) {
            await(host.stop(), START_TIMEOUT_SECONDS, "stop Connect Share direct host");
        }
        host = null;
        started = false;
    }

    private void ensureGuestHost(boolean listenerRequired) {
        if (host == null) {
            host = listenerRequired
                    ? Libp2pTunnelTransportRuntime.createHost(
                            privateKey,
                            "/ip4/0.0.0.0/tcp/0")
                    : Libp2pTunnelTransportRuntime.createHost(privateKey);
            installProtocols(host);
            startHostIfNeeded();
        } else if (listenerRequired && host.listenAddresses().isEmpty()) {
            await(
                    host.getNetwork().listen(
                            Multiaddr.fromString("/ip4/0.0.0.0/tcp/0")),
                    START_TIMEOUT_SECONDS,
                    "listen for Connect Share LAN discovery");
        }
    }

    private void installProtocols(Host target) {
        target.addProtocolHandler(new TunnelProtocolBinding());
        target.addProtocolHandler(new InfoProtocolBinding());
    }

    private synchronized void startMdns() {
        if (discovery != null) {
            return;
        }
        InetAddress address = MdnsAddressSelector.systemAddress();
        // JmDNS derives a host name with InetAddress#getHostName when none is
        // supplied. That can issue an unbounded reverse-DNS lookup and made
        // share startup hang for a full minute on otherwise healthy LANs.
        // The authenticated peer ID already gives this process a stable,
        // collision-resistant local name without touching DNS.
        String peerId = host.getPeerId().toBase58();
        JmDNS started = JmDNS.create(address, mdnsHostName(peerId));
        try {
            started.start();
            List<Inet4Address> ipv4Addresses = address instanceof Inet4Address
                    ? Collections.singletonList((Inet4Address) address)
                    : Collections.emptyList();
            List<Inet6Address> ipv6Addresses = address instanceof Inet6Address
                    ? Collections.singletonList((Inet6Address) address)
                    : Collections.emptyList();
            started.registerService(ServiceInfo.create(
                    MDNS_SERVICE,
                    peerId,
                    listenTcpPort(host),
                    peerId,
                    ipv4Addresses,
                    ipv6Addresses));
            started.addAnswerListener(
                    MDNS_SERVICE,
                    MDNS_QUERY_INTERVAL_SECONDS,
                    this::onMdnsAnswers);
            discovery = started;
        } catch (IOException | RuntimeException failure) {
            started.stop();
            throw new IllegalStateException(
                    "Could not start Connect Share LAN discovery",
                    failure);
        }
    }

    static String mdnsHostName(String peerId) {
        Objects.requireNonNull(peerId, "peerId");
        int prefixLength = Math.min(32, peerId.length());
        return "connect-share-" + peerId.substring(0, prefixLength);
    }

    private void onMdnsAnswers(List<DNSRecord> answers) {
        Host current = host;
        if (current == null) {
            return;
        }
        String localPeerId = current.getPeerId().toBase58();
        List<DNSRecord.Address> addresses = new ArrayList<>();
        for (DNSRecord answer : answers) {
            if (answer instanceof DNSRecord.Address) {
                addresses.add((DNSRecord.Address) answer);
            }
        }
        if (addresses.isEmpty()) {
            return;
        }
        for (DNSRecord answer : answers) {
            if (!(answer instanceof DNSRecord.Service)) {
                continue;
            }
            DNSRecord.Service service = (DNSRecord.Service) answer;
            for (DNSRecord candidate : answers) {
                if (!(candidate instanceof DNSRecord.Text)
                        || !candidate.getName().equalsIgnoreCase(service.getName())) {
                    continue;
                }
                String peerId;
                try {
                    peerId = decodeMdnsPeerId(((DNSRecord.Text) candidate).getText());
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (localPeerId.equals(peerId)) {
                    continue;
                }
                String inspection = peerId + ':' + service.getPort();
                if (!mdnsInspections.add(inspection)) {
                    continue;
                }
                List<Multiaddr> candidates = new ArrayList<>();
                for (DNSRecord.Address record : addresses) {
                    InetAddress discoveredAddress = record.getAddress();
                    String protocol = discoveredAddress instanceof Inet4Address
                            ? "ip4"
                            : "ip6";
                    try {
                        candidates.add(Multiaddr.fromString(
                                "/" + protocol + "/" + discoveredAddress.getHostAddress()
                                        + "/tcp/" + service.getPort()));
                    } catch (RuntimeException ignored) {
                        // Ignore unusable scoped or malformed answer records.
                    }
                }
                if (candidates.isEmpty()) {
                    mdnsInspections.remove(inspection);
                    continue;
                }
                Thread inspectionThread = new Thread(() -> {
                    try {
                        onMdnsPeer(new PeerInfo(
                                PeerId.fromBase58(peerId),
                                candidates));
                    } finally {
                        mdnsInspections.remove(inspection);
                    }
                }, "connect-share-mdns-answer");
                inspectionThread.setDaemon(true);
                inspectionThread.start();
            }
        }
    }

    static String decodeMdnsPeerId(byte[] text) {
        Objects.requireNonNull(text, "text");
        if (text.length == 0) {
            throw new IllegalArgumentException("mDNS peer ID is empty");
        }
        int offset = Byte.toUnsignedInt(text[0]) == text.length - 1 ? 1 : 0;
        String peerId = new String(
                text,
                offset,
                text.length - offset,
                StandardCharsets.UTF_8);
        PeerId.fromBase58(peerId);
        return peerId;
    }

    private void onMdnsPeer(PeerInfo peer) {
        Host current = host;
        DirectP2pDiscoveryListener listener = discoveryListener;
        if (current == null || listener == null
                || current.getPeerId().equals(peer.getPeerId())) {
            return;
        }
        Thread inspectThread = new Thread(() -> {
            for (Multiaddr candidate : peer.getAddresses()) {
                String address = candidate.withP2P(peer.getPeerId()).toString();
                try {
                    DirectP2pDiscoveredShare found =
                            inspect(address, Duration.ofSeconds(3));
                    if (discoveredInvitations.add(found.invitation())) {
                        listener.onDiscovered(found);
                    }
                    return;
                } catch (RuntimeException ignored) {
                    // Try the next address announced for this LAN peer.
                }
            }
        }, "connect-share-mdns-inspect");
        inspectThread.setDaemon(true);
        inspectThread.start();
    }

    private synchronized void startHostIfNeeded() {
        if (!started) {
            await(host.start(), START_TIMEOUT_SECONDS, "start Connect Share direct host");
            started = true;
        }
    }

    private void accept(Stream stream, DirectPreface preface) {
        DirectP2pHostConfig config = hostConfig;
        DirectP2pHostHandler handler = hostHandler;
        if (config == null || handler == null
                || !config.shareId().equals(preface.shareId)
                || !config.capability().equals(preface.capability)) {
            stream.close();
            return;
        }
        try {
            DirectP2pSession session = new DirectP2pSession(
                    stream.remotePeerId().toBase58(),
                    preface.authMode,
                    route(stream),
                    UUID.randomUUID().toString());
            Socket socket = handler.openLocalSession(session);
            if (socket == null || !socket.isConnected() || socket.isClosed()) {
                closeQuietly(socket);
                stream.close();
                return;
            }
            SocketBridge.install(stream, socket, "connect-share-direct-host");
        } catch (Exception e) {
            stream.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Connect Share direct runtime is closed");
        }
    }

    private static int listenTcpPort(Host host) {
        for (Multiaddr address : host.listenAddresses()) {
            MultiaddrComponent tcp = address.getFirstComponent(Protocol.TCP);
            if (tcp != null) {
                return Integer.parseInt(tcp.getStringValue());
            }
        }
        throw new IllegalStateException("Connect Share direct host has no TCP listener");
    }

    private static DirectP2pRoute route(Stream stream) {
        Multiaddr remote = stream.getConnection().remoteAddress();
        MultiaddrComponent ip = remote.getFirstComponent(Protocol.IP4);
        if (ip == null) {
            ip = remote.getFirstComponent(Protocol.IP6);
        }
        if (ip == null) {
            return DirectP2pRoute.INTERNET;
        }
        try {
            InetAddress address = InetAddress.getByName(ip.getStringValue());
            return address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    ? DirectP2pRoute.LAN
                    : DirectP2pRoute.INTERNET;
        } catch (IOException ignored) {
            return DirectP2pRoute.INTERNET;
        }
    }

    private static List<String> addresses(int port, String peerId, boolean internetOnly) {
        List<String> result = new ArrayList<>();
        if (!internetOnly) {
            result.add("/ip4/127.0.0.1/tcp/" + port + "/p2p/" + peerId);
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)
                            || address.isAnyLocalAddress()
                            || address.isLoopbackAddress()
                            || address.isMulticastAddress()) {
                        continue;
                    }
                    boolean publicAddress = !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()
                            && !address.isSiteLocalAddress();
                    if (internetOnly != publicAddress) {
                        continue;
                    }
                    result.add("/ip4/" + address.getHostAddress()
                            + "/tcp/" + port + "/p2p/" + peerId);
                }
            }
        } catch (SocketException e) {
            throw new IllegalStateException("Could not enumerate direct network addresses", e);
        }
        return Collections.unmodifiableList(result);
    }

    private static byte[] x509PublicKey(byte[] raw) {
        byte[] encoded = Arrays.copyOf(
                ED25519_X509_PREFIX,
                ED25519_X509_PREFIX.length + raw.length);
        System.arraycopy(raw, 0, encoded, ED25519_X509_PREFIX.length, raw.length);
        return encoded;
    }

    private static String requireInvitation(String value) {
        Objects.requireNonNull(value, "invitation");
        if (!value.startsWith("minekube://share/")
                || value.length() > MAX_INFO_SIZE) {
            throw new IllegalArgumentException("Connect Share invitation is invalid");
        }
        return value;
    }

    private byte[] encodeInfoResponse() {
        String currentInvitation = invitation;
        DirectP2pHostConfig currentConfig = hostConfig;
        if (currentInvitation == null || currentConfig == null) {
            return null;
        }
        try {
            String safeDisplayName = currentConfig.displayName()
                    .replace('\n', ' ')
                    .replace('\r', ' ');
            byte[] body = (safeDisplayName + "\n" + currentInvitation)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (body.length > MAX_INFO_SIZE) {
                throw new IllegalArgumentException(
                        "Connect Share discovery metadata is too large");
            }
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            writeVarint(frame, body.length);
            frame.write(body);
            return frame.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not encode Connect Share discovery metadata",
                    e);
        }
    }

    private static InfoResponse decodeInfoResponse(byte[] body) {
        try {
            String value = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            int separator = value.indexOf('\n');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException(
                        "Connect Share discovery metadata is invalid");
            }
            String displayName = value.substring(0, separator);
            String invitation = requireInvitation(value.substring(separator + 1));
            return new InfoResponse(displayName, invitation);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Connect Share discovery metadata is invalid",
                    e);
        }
    }

    private static byte[] encodePreface(DirectPreface preface) {
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(body)) {
                out.writeInt(PREFACE_MAGIC);
                out.writeInt(WIRE_VERSION);
                out.writeUTF(preface.shareId);
                out.writeUTF(preface.capability);
                out.writeByte(preface.authMode.ordinal());
            }
            if (body.size() > MAX_PREFACE_SIZE) {
                throw new IllegalArgumentException("Connect Share direct preface is too large");
            }
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            writeVarint(frame, body.size());
            body.writeTo(frame);
            return frame.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode Connect Share direct preface", e);
        }
    }

    private static DirectPreface decodePreface(byte[] body) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readInt() != PREFACE_MAGIC) {
                throw new IllegalArgumentException("Invalid Connect Share direct preface");
            }
            int version = input.readInt();
            if (version != WIRE_VERSION) {
                throw new IllegalArgumentException("Unsupported Connect Share direct version");
            }
            String shareId = input.readUTF();
            String capability = input.readUTF();
            int authMode = input.readUnsignedByte();
            if (authMode >= DirectP2pAuthMode.values().length || input.available() != 0) {
                throw new IllegalArgumentException("Invalid Connect Share direct authentication mode");
            }
            return new DirectPreface(
                    shareId,
                    capability,
                    DirectP2pAuthMode.values()[authMode]);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid Connect Share direct preface", e);
        }
    }

    private static void writeVarint(ByteArrayOutputStream output, int value) {
        int current = value;
        while ((current & ~0x7f) != 0) {
            output.write((current & 0x7f) | 0x80);
            current >>>= 7;
        }
        output.write(current);
    }

    private static int readFrameLength(ByteBuf input, int maximum) {
        input.markReaderIndex();
        int length = 0;
        int shift = 0;
        for (int index = 0; index < 5; index++) {
            if (!input.isReadable()) {
                input.resetReaderIndex();
                return -1;
            }
            int current = input.readUnsignedByte();
            length |= (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                if (length <= 0 || length > maximum) {
                    throw new IllegalArgumentException(
                            "Connect Share frame size is invalid: " + length);
                }
                return length;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("Connect Share frame length overflow");
    }

    private static <T> T await(
            CompletableFuture<T> future,
            Duration timeout,
            String action) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to " + action, e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("Timed out while trying to " + action, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to " + action, e);
        }
    }

    private static <T> T await(
            CompletableFuture<T> future,
            long timeoutSeconds,
            String action) {
        return await(future, Duration.ofSeconds(timeoutSeconds), action);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort after a stream closes.
        }
    }

    private final class TunnelProtocolBinding extends StrictProtocolBinding<Void> {
        private TunnelProtocolBinding() {
            super(TUNNEL_PROTOCOL_ID, new TunnelProtocolHandler());
        }
    }

    private final class InfoProtocolBinding
            extends StrictProtocolBinding<InfoController> {
        private InfoProtocolBinding() {
            super(INFO_PROTOCOL_ID, new InfoProtocolHandler());
        }
    }

    private final class InfoProtocolHandler
            extends ProtocolHandler<InfoController> {
        private InfoProtocolHandler() {
            super(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        @Override
        protected CompletableFuture<InfoController> onStartInitiator(Stream stream) {
            return CompletableFuture.completedFuture(new InfoController(stream));
        }

        @Override
        protected CompletableFuture<InfoController> onStartResponder(Stream stream) {
            byte[] response = encodeInfoResponse();
            CompletableFuture.runAsync(() -> {
                if (response == null) {
                    stream.close();
                } else {
                    stream.writeAndFlush(Unpooled.wrappedBuffer(response));
                    stream.closeWrite();
                }
            });
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class InfoController {
        private final CompletableFuture<InfoResponse> response =
                new CompletableFuture<>();

        private InfoController(Stream stream) {
            InfoResponseDecoder decoder = new InfoResponseDecoder();
            stream.pushHandler(decoder);
            stream.pushHandler(new InfoResponseHandler(stream, decoder, response));
        }
    }

    private static final class InfoResponseHandler
            extends SimpleChannelInboundHandler<InfoResponse> {
        private final Stream stream;
        private final InfoResponseDecoder decoder;
        private final CompletableFuture<InfoResponse> response;

        private InfoResponseHandler(
                Stream stream,
                InfoResponseDecoder decoder,
                CompletableFuture<InfoResponse> response) {
            this.stream = stream;
            this.decoder = decoder;
            this.response = response;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, InfoResponse message) {
            response.complete(message);
            context.pipeline().remove(this);
            context.pipeline().remove(decoder);
            stream.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            response.completeExceptionally(
                    new IllegalStateException("Connect Share metadata stream closed"));
            super.channelInactive(context);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            response.completeExceptionally(cause);
            stream.close();
            context.close();
        }
    }

    private static final class InfoResponseDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(
                ChannelHandlerContext context,
                ByteBuf input,
                List<Object> output) {
            int size = readFrameLength(input, MAX_INFO_SIZE);
            if (size < 0) {
                return;
            }
            if (input.readableBytes() < size) {
                input.resetReaderIndex();
                return;
            }
            byte[] frame = new byte[size];
            input.readBytes(frame);
            output.add(decodeInfoResponse(frame));
        }
    }

    private static final class InfoResponse {
        private final String displayName;
        private final String invitation;

        private InfoResponse(String displayName, String invitation) {
            this.displayName = displayName;
            this.invitation = invitation;
        }
    }

    private final class TunnelProtocolHandler extends ProtocolHandler<Void> {
        private TunnelProtocolHandler() {
            super(Long.MAX_VALUE, Long.MAX_VALUE);
        }

        @Override
        protected CompletableFuture<Void> onStartInitiator(Stream stream) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        protected CompletableFuture<Void> onStartResponder(Stream stream) {
            DirectPrefaceDecoder decoder = new DirectPrefaceDecoder();
            stream.pushHandler(decoder);
            stream.pushHandler(new DirectPrefaceHandler(stream, decoder));
            return CompletableFuture.completedFuture(null);
        }
    }

    private final class DirectPrefaceHandler
            extends SimpleChannelInboundHandler<DirectPreface> {
        private final Stream stream;
        private final DirectPrefaceDecoder decoder;

        private DirectPrefaceHandler(Stream stream, DirectPrefaceDecoder decoder) {
            this.stream = stream;
            this.decoder = decoder;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, DirectPreface preface) {
            context.pipeline().remove(this);
            context.pipeline().remove(decoder);
            accept(stream, preface);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            stream.close();
            context.close();
        }
    }

    private static final class DirectPrefaceDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(
                ChannelHandlerContext context,
                ByteBuf input,
                List<Object> output) {
            int size = readFrameLength(input, MAX_PREFACE_SIZE);
            if (size < 0) {
                return;
            }
            if (input.readableBytes() < size) {
                input.resetReaderIndex();
                return;
            }
            byte[] frame = new byte[size];
            input.readBytes(frame);
            output.add(decodePreface(frame));
        }
    }

    private static final class DirectPreface {
        private final String shareId;
        private final String capability;
        private final DirectP2pAuthMode authMode;

        private DirectPreface(
                String shareId,
                String capability,
                DirectP2pAuthMode authMode) {
            this.shareId = Objects.requireNonNull(shareId, "shareId");
            this.capability = Objects.requireNonNull(capability, "capability");
            this.authMode = Objects.requireNonNull(authMode, "authMode");
        }
    }

    private static final class ProxyRuntime implements AutoCloseable {
        private final Host host;
        private final String address;
        private final DirectPreface preface;
        private final Duration timeout;
        private final ServerSocket listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Socket client;
        private volatile Stream stream;

        private ProxyRuntime(
                Host host,
                String address,
                DirectPreface preface,
                Duration timeout) throws IOException {
            this.host = host;
            this.address = Objects.requireNonNull(address, "address");
            this.preface = Objects.requireNonNull(preface, "preface");
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            listener = new ServerSocket();
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
        }

        private InetSocketAddress localAddress() {
            return (InetSocketAddress) listener.getLocalSocketAddress();
        }

        private void start() {
            Multiaddr multiaddr = Multiaddr.fromString(address);
            PeerId peerId = multiaddr.getPeerId();
            if (peerId == null) {
                throw new IllegalArgumentException(
                        "direct address must include /p2p/<peer-id>");
            }
            Connection connection = await(
                    host.getNetwork().connect(peerId, multiaddr),
                    timeout,
                    "dial the Connect Share host");
            StreamPromise<Object> promise = host.newStream(
                    Collections.singletonList(TUNNEL_PROTOCOL_ID),
                    connection);
            stream = await(
                    promise.getStream(),
                    timeout,
                    "open the Connect Share direct stream");
            await(
                    stream.getProtocol(),
                    timeout,
                    "negotiate the Connect Share direct protocol");

            Thread thread = new Thread(this::acceptAndBridge, "connect-share-direct-guest");
            thread.setDaemon(true);
            thread.start();
        }

        private void acceptAndBridge() {
            try {
                client = listener.accept();
                listener.close();
                SocketBridge.install(
                        stream,
                        client,
                        "connect-share-direct-guest",
                        encodePreface(preface));
            } catch (Exception failure) {
                close();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                listener.close();
            } catch (IOException ignored) {
                // Best effort during share shutdown.
            }
            closeQuietly(client);
            Stream active = stream;
            if (active != null) {
                active.close();
            }
        }
    }

    private static final class SocketBridge {
        private SocketBridge() {
        }

        private static void install(Stream stream, Socket socket, String threadName)
                throws IOException {
            install(stream, socket, threadName, null);
        }

        private static void install(
                Stream stream,
                Socket socket,
                String threadName,
                byte[] initialFrame) throws IOException {
            AtomicBoolean closed = new AtomicBoolean();
            stream.pushHandler(new StreamToSocketHandler(stream, socket, closed));
            if (initialFrame != null) {
                stream.writeAndFlush(Unpooled.wrappedBuffer(initialFrame));
            }
            Thread outbound = new Thread(
                    () -> copySocketToStream(stream, socket, closed),
                    threadName);
            outbound.setDaemon(true);
            outbound.start();
        }

        private static void copySocketToStream(
                Stream stream,
                Socket socket,
                AtomicBoolean closed) {
            byte[] buffer = new byte[16 * 1024];
            try {
                InputStream input = socket.getInputStream();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        stream.writeAndFlush(
                                Unpooled.wrappedBuffer(Arrays.copyOf(buffer, read)));
                    }
                }
                stream.closeWrite();
            } catch (IOException ignored) {
                close(stream, socket, closed);
            }
        }

        private static void close(Stream stream, Socket socket, AtomicBoolean closed) {
            if (closed.compareAndSet(false, true)) {
                closeQuietly(socket);
                stream.close();
            }
        }
    }

    private static final class StreamToSocketHandler
            extends SimpleChannelInboundHandler<ByteBuf> {
        private final Stream stream;
        private final Socket socket;
        private final AtomicBoolean closed;

        private StreamToSocketHandler(
                Stream stream,
                Socket socket,
                AtomicBoolean closed) {
            this.stream = stream;
            this.socket = socket;
            this.closed = closed;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext context, ByteBuf message)
                throws IOException {
            socket.getOutputStream().write(
                    ByteBufUtil.getBytes(
                            message,
                            message.readerIndex(),
                            message.readableBytes(),
                            true));
            socket.getOutputStream().flush();
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) throws Exception {
            SocketBridge.close(stream, socket, closed);
            super.channelInactive(context);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
            SocketBridge.close(stream, socket, closed);
            context.close();
        }
    }
}
