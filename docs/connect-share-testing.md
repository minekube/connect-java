# Connect Share acceptance

Connect Share is built separately for Minecraft Java 1.21.11 on Java 21 and
Minecraft Java 26.2 on Java 25. Run this pass against both artifacts before
calling the singleplayer and direct-sharing implementation release-ready.

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

1. Start a singleplayer world and choose **Share with friends**.
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
7. Confirm a valid import keeps the dashboard endpoint name.
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

## Modded same-LAN direct joins

Use two machines on the same LAN with the matching Connect Share artifact.
Connect may remain configured, but temporarily block the guest from reaching
the host's `*.play.minekube.net` address so a successful join proves the direct
route works.

1. Start a host world, choose **Share with friends**, and leave
   **Allow faster direct internet connections** disabled.
2. On the guest title screen, choose **Friends**, then **Join Connect Share**.
3. Confirm the host world appears automatically as a nearby share. The host
   must not use Minecraft's **Open to LAN** action.
4. Choose the nearby world with the default online identity. Confirm the host
   receives an authenticated direct-LAN approval request, can deny it, and can
   approve a later attempt.
5. Repeat with **Use an offline identity (unverified)**. Confirm the host sees
   an unverified identity and approval is not reused for a later connection.
6. Confirm the guest joins while the Connect hostname remains blocked.
7. Stop sharing and confirm discovery disappears and the old signed invitation
   cannot create a usable direct session while the host is stopped.
8. Start sharing again. Confirm the saved libp2p peer identity and access
   identity are reused while the persistent Connect endpoint remains unchanged.

## Invitation, internet-direct, and fallback behavior

Internet-direct is best-effort and requires an actually reachable public
address, such as a publicly routed host or an explicitly configured network.
The mod does not open a public Minecraft listener, configure UPnP, or use a
self-hosted libp2p relay.

1. Copy the signed invitation from the host status screen and paste it into
   **Join Connect Share** on a guest outside the LAN.
2. With internet-direct disabled on either peer, confirm the guest does not
   attempt a direct internet route and uses Connect once.
3. Enable internet-direct on both peers. Confirm both UIs disclose that the
   path reveals public IP addresses before it is attempted.
4. On a directly reachable network, confirm the direct route succeeds and the
   host approval identifies it as internet-direct.
5. Make the advertised direct address unreachable while leaving Connect
   available. Confirm one bounded direct attempt is followed by exactly one
   Connect attempt and the guest can still join.
6. Repeat without a usable Connect ingress. Confirm same-LAN sharing remains
   available, while a relay-required remote guest receives a safe no-route
   failure.
7. Modify, truncate, expire, or reuse a signed invitation with a different
   libp2p peer address. Confirm it is rejected before Minecraft connects and no
   capability, candidate, endpoint token, or signature bytes appear in logs.

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
9. Join a direct share, disconnect, and wait for the title screen. Confirm the
   guest loopback proxy and discovery node close. Abort a direct connection
   before login and confirm the same resources close after the bounded timeout.

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
The nested payload must include
`com/minekube/connect/tunnel/p2p/DirectP2pNodeRuntime.class`.

## Evidence to retain

Record the host and guest Minecraft versions, Java versions, artifact SHA-256
digests, endpoint name, admission outcomes, listener scan result, and relevant
redacted log excerpts. Never retain an endpoint token, invitation secret, or
direct-connect candidate in test evidence.
