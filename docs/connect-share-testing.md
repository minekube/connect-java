# Connect Share singleplayer acceptance

Connect Share is built separately for Minecraft Java 1.21.11 on Java 21 and
Minecraft Java 26.2 on Java 25. Run this pass against both artifacts before
calling the singleplayer slice release-ready.

The mod build does not publish a Connect Java plugin release, rebuild a hub
image, or roll anything out to production.

## Build the artifacts

From the repository root:

```sh
./gradlew :share:fabric-1-21-11:build
./gradlew :share:fabric-26-2:build
```

Use the unclassified versioned JAR in each module's `build/libs` directory.
Do not install `sources`, `dev`, `unshaded`, or `parent-shadow` artifacts.
Install the matching Fabric Loader, Fabric API, and Fabric Language Kotlin.

## Identity reuse and import

1. Start a singleplayer world and choose **Share with Connect**.
2. Record the displayed endpoint and a cryptographic digest of
   `config/minekube-connect-share/token.json`. Do not copy the token into test
   notes or logs.
3. Stop sharing, share the same world again, then share a different world.
4. Confirm the endpoint and token digest remain byte-for-byte identical. No new
   endpoint record should appear for either world.
5. Import a dashboard-created endpoint and token. Repeat using a
   plugin-compatible `token.json`.
6. Confirm a deliberately invalid endpoint or token is rejected and leaves the
   previous endpoint and token files unchanged.
7. Confirm a valid import keeps the dashboard endpoint name, including any
   hostname or custom-domain configuration attached to it.
8. Start once with `CONNECT_ENDPOINT` and `CONNECT_TOKEN`. Confirm both fields
   are shown as environment-managed and cannot be edited or reset in the UI.

## Vanilla guest joins and admission

For each supported host version:

1. Start sharing and copy the displayed `*.play.minekube.net` address.
2. Join from an unmodified paid Java client through Connect.
3. Confirm the host sees the guest's name, UUID, and authenticated source before
   the tunnel reaches the integrated server.
4. Deny the request and confirm the guest does not enter the world.
5. Reconnect, allow the request, and confirm the guest enters.
6. Reconnect the same authenticated profile during the same share and confirm
   the current-share approval is reused.
7. Join from an unmodified non-paid/offline-mode client.
8. Deny once, reconnect, then allow. Confirm an offline approval applies only to
   that individual connection and is not silently reused.
9. Fill the configured guest capacity and confirm additional guests receive a
   safe full-share rejection.

## Listener and lifecycle safety

1. While sharing, scan the host from another LAN device. Confirm Minecraft's
   chosen TCP port is not reachable on any LAN or wildcard address.
2. Confirm no vanilla LAN multicast advertisement is emitted.
3. Close the status screen without stopping. Confirm the share remains active.
4. Use **Stop sharing** and confirm the public hostname no longer reaches the
   world.
5. Leave the world while sharing. Confirm shutdown runs exactly once.
6. Start a different integrated world and confirm the previous share is closed
   before the replacement becomes available.
7. Quit Minecraft while sharing and confirm the Connect watcher, local channel,
   loopback listener, isolated libp2p loader, and temporary runtime payload all
   close.
8. Repeat start/stop twice and compare thread and channel counts. There must be
   no accumulating Connect, Netty, watcher, or coroutine resources.

## Artifact inspection

Inspect the final JARs:

```sh
jar tf share/fabric-1.21.11/build/libs/connect-share-fabric-1.21.11-*.jar
jar tf share/fabric-26.2/build/libs/connect-share-fabric-26.2-*.jar
```

Each final artifact must contain:

- `fabric.mod.json`;
- the version-specific Connect Share mixin JSON;
- English and German translations;
- `LICENSE`;
- `com/minekube/connect/share/` classes; and
- `META-INF/connect/libp2p-runtime.jar`.

It must not contain top-level `io/libp2p/`, `io/netty/`, or `kotlin/`
packages. Those runtime classes belong only inside the child-loaded payload.

## Evidence to retain

Record the host and guest Minecraft versions, Java versions, artifact SHA-256
digests, endpoint name, admission outcomes, listener scan result, and relevant
redacted log excerpts. Never retain an endpoint token, invitation secret, or
direct-connect candidate in test evidence.
