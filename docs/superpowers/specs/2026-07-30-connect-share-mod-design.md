# Connect Share Mod Design

**Date:** 2026-07-30  
**Status:** Approved for implementation
**Epic:** [minekube/connect-java#83](https://github.com/minekube/connect-java/issues/83)

## Summary

Connect Share is a client-side Minecraft mod that lets a player share the
singleplayer world they are currently playing. The host installs the mod.
Vanilla guests can join through the host's Minekube Connect endpoint while the
share is active. Guests with the mod can additionally use a direct libp2p
connection when both sides permit it.

Connect is the private default and the only relay fallback. The mod does not
operate, recommend, or configure an independent public relay. Same-LAN direct
connections are automatic. Internet direct connections are attempted only when
both host and guest explicitly opt in because that path reveals their public IP
addresses to each other.

The first release supports Fabric on Minecraft 1.21.11 and 26.2. Application
logic is written in Kotlin and shared across both versions.

## Product Decisions

- The host starts sharing from a dedicated **Share with Connect** pause-menu
  action; they do not press Minecraft's Open to LAN button.
- No listener is exposed on a LAN or WAN interface.
- The mod creates one Connect endpoint identity per Minecraft installation and
  persists its endpoint name and token like the Connect plugin. Every world
  reuses that identity, so repeated shares do not create control-plane endpoint
  records.
- A player who already created or imported an endpoint in the Minekube
  Dashboard can import that exact endpoint name and dashboard-issued token
  instead of creating another endpoint.
- Stopping the share or leaving the world makes the stable endpoint
  unreachable until the host explicitly starts another share.
- Connect supplies the vanilla guest's profile and authentication type at the
  edge. The mod preserves that session context when it injects the connection
  locally.
- Online and offline-mode Java accounts are supported, matching Connect. A
  profile authenticated by the managed Connect edge or by Mojang may be
  remembered for the current share. A locally accepted offline profile is
  visibly labeled unverified and approved per connection so a copied username
  cannot inherit an earlier approval.
- Same-LAN mod-to-mod traffic is attempted automatically through direct
  libp2p discovery and dialing.
- Internet P2P is disabled by default. Both peers must enable it for the
  current connection attempt.
- Connect is the only fallback when direct connectivity fails. Without
  Connect, same-LAN and otherwise directly reachable peers can still connect;
  NAT combinations that require a relay fail with an actionable message.
- Fabric builds are published for Minecraft 1.21.11 and 26.2. NeoForge is a
  later adapter, not part of this implementation.

## Goals

1. Let a host share an integrated singleplayer server without port forwarding
   or a publicly bound LAN listener.
2. Let an unmodified Java client join through the host's Connect hostname
   while sharing is active.
3. Reuse Connect's session identity, authentication-type, and tunnel semantics
   instead of creating a parallel public ingress service.
4. Let two modded clients connect directly on the same LAN without Connect.
5. Let two modded clients optionally attempt a direct internet connection,
   falling back to Connect when available.
6. Keep the Minecraft-version hooks small and keep lifecycle, admission,
   invitation, and transport selection independently testable.
7. Let an endpoint owner reuse a dashboard-managed endpoint, token, public
   hostname, and attached custom domains without creating a duplicate endpoint.
8. Accept both online and offline-mode Java guests while presenting whether
   identity was authenticated by Connect, Mojang, or neither.

## Non-goals

- Dedicated-server or current-multiplayer-server sharing
- World synchronization or host migration
- A friend graph, social network, or persistent invitations
- UPnP-based public Minecraft TCP listeners
- An independent libp2p relay network
- Bedrock guest support in the first mod release
- Voice chat tunneling
- NeoForge, Forge, or Quilt artifacts in the first release

## Build and Module Structure

The existing plugin build remains intact. Mod releases and plugin releases are
separate products and separate workflows.

The mod is organized into focused modules:

```text
share/
├── common/                 Kotlin state, policy, invitations, and transport selection
├── fabric-common/          Fabric entrypoint and loader integration shared by both versions
├── fabric-1.21.11/         Java 21 Minecraft adapter and mixins
└── fabric-26.2/            Java 25 Minecraft adapter and mixins
```

`share/common` contains no version-specific Minecraft classes. It owns public
interfaces such as `ShareCoordinator`, `AdmissionController`,
`TransportSelector`, `ShareInviteCodec`, and `ShareState`.

`share/fabric-common` owns screens, translations, Fabric lifecycle wiring, and
the adapter-neutral glue between Minecraft and `share/common`.

Each version module implements `MinecraftShareBridge`, which is the only
component allowed to depend on version-specific integrated-server and login
classes. Mixins and accessors stay in these modules. Handwritten application
logic is Kotlin. A minimal Java mixin or accessor shim is permitted only when
Mixin's generated bytecode or annotation processing requires a stable Java
signature; such a shim contains no product logic.

The build pins:

- Fabric Loader `0.19.3`
- Fabric API `0.141.6+1.21.11` for Minecraft 1.21.11
- Fabric API `0.156.0+26.2` for Minecraft 26.2
- Fabric Language Kotlin `1.13.13+kotlin.2.4.10`
- jvm-libp2p `1.3.5`
- Java toolchain 21 for Minecraft 1.21.11
- Java toolchain 25 for Minecraft 26.2

The wire protocol has its own integer version and does not use the mod artifact
version as a compatibility signal.

## Component Boundaries

### ShareCoordinator

Owns the single active share and its state machine:

```text
IDLE -> STARTING -> SHARING -> STOPPING -> IDLE
                   \-> DEGRADED
        \--------------------> FAILED
```

It starts and stops the Minecraft bridge, Connect ingress, and direct P2P
service in a fixed order. Stop is idempotent and always attempts every cleanup
step. A world change, disconnect, game shutdown, or integrated-server halt
stops the share.

`DEGRADED` means at least one usable ingress remains. For example, Connect may
be unavailable while same-LAN direct sharing continues. `FAILED` means no
ingress is usable and the local bridge has been closed.

### MinecraftShareBridge

Publishes the integrated server for remote sessions without exposing it on a
network interface.

The adapter invokes Minecraft's integrated-server publishing lifecycle with a
loopback-only TCP listener so vanilla initializes its normal connection
pipeline. It captures the resulting child `ChannelInitializer` and event loop,
then binds a Connect `LocalServerChannelWrapper` using that initializer.
Connect's `LocalChannelWithSessionContext` carries the profile,
authentication type, and other Connect session data into the accepted local
channel.

The loopback listener is an implementation detail and is never advertised.
External sessions use the in-memory local channel. This is deliberately safer
than manually reconstructing a Minecraft `Connection` and less invasive than
trying to bypass the publishing lifecycle completely.

The bridge also provides the version-specific hook that pauses login until
`AdmissionController` accepts or rejects it. It can accept a profile already
authenticated by Connect, run normal Mojang authentication, or initialize the
vanilla-compatible offline profile without changing unrelated local play.

### ConnectShareIngress

Loads or creates one persistent Connect identity for the Minecraft
installation:

```text
config/minekube-connect-share/config.json
config/minekube-connect-share/token.json
```

`config.json` stores the endpoint name and non-secret user settings.
`token.json` stores the endpoint token using the same `{"token":"T-..."}`
shape as the Connect plugin. The token is created once, written with
owner-only permissions where the operating system supports them, and redacted
from logs and UI. The standard `CONNECT_ENDPOINT` and `CONNECT_TOKEN`
environment variables override the files for compatibility with existing
Connect deployments and managed launchers.

Environment overrides are resolved per field, matching the existing plugin:
`CONNECT_ENDPOINT` overrides the stored endpoint name and `CONNECT_TOKEN`
overrides `token.json`. While either override is active, the corresponding
field is marked **Managed by environment** and cannot be changed or reset from
the in-game UI.

Every world share starts the existing Connect watch/libp2p connector runtime
with this identity and stops it with the share. No share or world identifier
is used as an endpoint name. The database therefore contains at most one
endpoint per mod installation unless the user explicitly resets their
identity.

An endpoint-token mismatch never triggers automatic endpoint or token
rotation. The UI explains the mismatch and lets the user restore the token or
explicitly choose **Reset Connect identity**. Resetting warns that it creates
a new endpoint and invalidates the old local identity.

The identity setup screen offers:

1. **Create a Connect endpoint**, which generates and persists one local
   endpoint identity using the normal connector behavior; and
2. **Use an existing dashboard endpoint**, which accepts an endpoint name and
   masked dashboard-issued token. The user may paste the token or select an
   existing plugin-compatible `token.json`.

Because a token is opaque and authorized for one endpoint in one Minekube
organization, importing a token always requires its endpoint name. The mod
stages the imported pair in memory, opens an authenticated Connect validation
session that rejects every player proposal, and atomically replaces the
persisted identity only after validation succeeds. This path also updates a
stored token after the owner resets that same endpoint's token in the
Dashboard. A mismatch, wrong organization, malformed token file, network
failure, cancellation, or game crash leaves the previously working identity
unchanged. Imported credentials are never regenerated by the mod.

The import screen warns that an endpoint should not simultaneously route from
another server or connector. If Connect reports a conflicting active
connector, sharing fails closed instead of allowing ambiguous routing.

For a non-passthrough Connect session, the proposal remains pending while the
host approves the Connect-authenticated profile; denial happens before a local
tunnel is opened. A passthrough session must open a bounded local tunnel so
Minecraft can perform online or offline login. That login is paused after its
profile is resolved and before the player enters the world, then presented for
host approval with its resulting trust level. The connector advertises support
for offline-mode players, as the Connect plugin can. Denial, timeout, world
shutdown, and capacity exhaustion fail closed at the earliest stage where the
session's identity is available.

### DirectP2pIngress

Reuses Connect Java's isolated jvm-libp2p runtime. The reflective classloader
boundary remains authoritative: `io.libp2p.*`, its Netty version, and its
Kotlin runtime never leak into Minecraft- or parent-loaded public signatures.

Every share creates an ephemeral libp2p identity so separate shares cannot be
correlated by a stable peer ID. The direct service supports:

- mDNS discovery and direct dialing on the same LAN;
- directly dialable IPv6 or explicitly mapped candidates;
- coordinated QUIC hole punching when candidate exchange is available;
- no circuit-relay candidates outside the managed Connect path.

The direct stream carries a small versioned preface followed by ordinary
Minecraft login bytes into the same local Minecraft initializer. The preface
declares the guest's requested authentication mode:

- online guests complete normal Mojang/Microsoft authentication before
  admission; and
- offline guests receive Minecraft's deterministic offline profile and are
  marked unverified before per-connection admission.

The direct protocol never silently downgrades a failed online login to offline
mode. The guest must already be operating in offline mode and explicitly
declares it in the mod-to-mod preface.

### ShareInviteCodec

A copied invitation is a versioned URI:

```text
minekube://share/{base64url-cbor-payload}
```

The signed payload contains:

- wire protocol version;
- share ID;
- expiry;
- persistent Connect hostname when Connect is available;
- ephemeral host peer ID;
- direct candidates only when the host enabled internet P2P;
- an unguessable per-share capability;
- the host peer signature over every preceding field.

The capability authorizes requesting admission; it never bypasses host
approval and never changes the guest's displayed authentication status.
Same-LAN discovery advertises the share ID, protocol version, peer ID, and a
short display name, but not the internet capability or public candidates.

An unmodified guest receives only the Connect hostname. A modded guest can
paste the URI into the Join Share screen. Pasting the URI into Minecraft's
Direct Connection field is detected by the mod and routed through the same
parser.

### AdmissionController

Admission uses an explicit identity type:

```text
AuthenticatedProfile(uuid, name, authSource = CONNECT | MOJANG)
UnverifiedOffline(offlineUuid, claimedName, connectionId, ingress)
```

For a new identity, the controller:

1. creates one pending request;
2. shows the host the name, UUID, authentication badge, and ingress type;
3. offers **Allow** and **Deny** actions;
4. expires the request after 30 seconds;
5. remembers an allowed authenticated UUID until this share stops; or
6. applies an unverified approval only to that connection.

Duplicate requests for the same authenticated UUID, or the same live offline
connection ID, share one decision. An offline reconnect creates a new request
even when its claimed name and deterministic offline UUID match. At most 16
requests may be pending, with bounded attempts per Connect session or direct
peer. Excess requests are rejected. Denial and timeout are visible to the
guest without exposing internal errors.

### TransportSelector

The modded guest applies this order:

1. If the discovered host is on the same LAN, try direct libp2p for 3 seconds.
2. If both peers enabled internet P2P, try direct candidates and coordinated
   QUIC punching for 5 seconds.
3. If a Connect hostname exists, join through Connect.
4. Otherwise report that no direct route was available and Connect was not
   enabled.

Internet candidate gathering and publication do not start until the host opts
in. The guest confirms the same privacy warning before an internet-direct
attempt. Failure falls back silently to Connect except for a concise status
indicator; it does not spam chat.

## User Experience

### Host

The pause menu contains **Share with Connect**. The setup screen shows:

- game mode;
- allow-cheats option;
- maximum guests, default 8 and range 1–16;
- **Allow direct internet connections**, off by default, with an IP-disclosure
  warning;
- **Start Sharing**.

While active, the screen shows:

- Connect address and copy button;
- copyable full mod invitation;
- Connect, LAN direct, and internet direct status separately;
- connected and approved players;
- pending approval cards;
- **Stop Sharing**.

Connect identity settings show the endpoint name, credential source
(generated, imported, or environment), and a masked token status. They provide
**Import existing endpoint** and the separately warned **Reset Connect
identity** action. The token value is never displayed again after a successful
import.

The host receives a toast and chat action when an approval is pending. Closing
the screen does not stop sharing.

### Guest

Vanilla guests add or directly connect to the host's Connect hostname. Modded
guests can use **Join Share** or paste a `minekube://share/` invitation. The
hostname is stable and is not treated as a secret; the displayed
authentication level and host approval remain the authorization boundary.

The guest sees which path won: **Direct LAN**, **Direct internet**, or
**Minekube Connect**. Internet-direct confirmation explains that both peers
will learn each other's IP address. Approval requests and the connected-player
list show **Connect authenticated**, **Verified online**, or **Unverified
offline**; the UI never presents a locally derived offline UUID or username as
authenticated.

## Security and Privacy

- Connect identity is accepted only from a session context produced by the
  managed Connect ingress.
- Profiles delivered by a non-passthrough managed Connect session are trusted
  as Connect-authenticated whether the player uses a paid or non-paid account.
- Direct online and Connect-passthrough online sessions complete normal
  Mojang/Microsoft authentication before admission.
- Locally accepted offline sessions are supported but explicitly marked
  unverified. Their approval is bound to one connection and cannot be reused by
  another client claiming the same username or deterministic offline UUID.
- Every ingress requires host approval under the admission identity rules.
- Approvals, share capabilities, and ephemeral peer identities die with the
  share. The Connect endpoint name and token persist across shares.
- The persistent endpoint token is stored separately from ordinary settings,
  never included in invitations, and redacted from logs and UI.
- Secrets and direct candidate addresses are redacted from normal logs.
- Internet P2P is opt-in on both peers and never inferred from merely having
  the mod installed.
- Direct P2P does not accept or advertise circuit-relay addresses.
- The host limits the share to 16 guests, 16 pending approvals, and one active
  share. Admission attempts are additionally bounded per Connect session or
  ephemeral direct peer.
- Malformed, expired, unsupported-version, incorrectly signed, or
  capability-mismatched invitations are rejected before dialing.

## Failure Handling

- If local bridge creation fails, sharing fails without starting any ingress.
- If Connect fails but a direct ingress is usable, the share enters
  `DEGRADED` and clearly says it is available only to modded direct peers.
- If direct setup fails, Connect sharing remains active.
- A failed direct guest attempt falls back to Connect when the invitation
  contains a Connect hostname.
- If Connect authentication rejects the endpoint identity, the UI shows the
  sanitized watch-service reason and offers token recovery or an explicit,
  warned identity reset. It never creates another endpoint automatically.
- All partial startup paths run the same idempotent stop sequence.
- Minecraft-version hook drift fails at startup with the affected version and
  mixin/accessor name; it never exposes a partially initialized share.

## Testing Strategy

### Common unit tests

- state-machine transitions and idempotent cleanup;
- persistent endpoint creation, reload, environment override, redaction,
  cross-world reuse, and explicit-only reset;
- dashboard credential paste and `token.json` import, staged validation,
  atomic replacement, rollback on every failure, and credential-source
  precedence;
- Connect-authenticated, Mojang-authenticated, and unverified admission allow,
  deny, duplicate, reconnect, impersonated-name, timeout, capacity, rate-limit,
  and share reset;
- invitation round-trip, signature, expiry, version, capability, and redaction;
- transport order, privacy opt-in, timeouts, and Connect fallback.

### Networking tests

- local Connect channel preserves `ConnectPlayer` session context;
- Connect ingress preserves passthrough/offloaded authentication semantics and
  accepts both paid and non-paid account modes;
- direct stream reaches the vanilla child initializer without a public bind;
- direct online authentication never downgrades to offline after failure;
- direct offline login creates an unverified profile and requires a fresh
  approval after reconnect;
- two loopback libp2p hosts exchange a Minecraft-shaped byte stream;
- direct configuration contains no circuit-relay candidate;
- failed direct dial selects Connect exactly once;
- runtime-isolation tests reject libp2p, Netty, or Kotlin types crossing the
  reflective parent boundary.

### Version tests

Both Fabric artifacts must:

- compile against their exact Minecraft and Fabric API versions;
- apply every mixin in a headless integrated-server startup smoke test;
- create and stop the local bridge twice in one process;
- package the correct `fabric.mod.json`, mixin config, translations, and
  dependency constraints;
- expose the same wire protocol fixtures.

### Build and CI

- Existing plugin verification remains `./gradlew build`.
- Mod verification builds on Java 21 and Java 25 as appropriate.
- CI verifies both remapped Fabric JARs and rejects duplicate or leaked
  unisolated networking classes.
- Release automation publishes mod artifacts separately from
  `connect-spigot.jar`, `connect-velocity.jar`, and `connect-bungee.jar`.

### Manual acceptance

Before calling the feature complete:

1. Share a 1.21.11 world and join from an unmodified client through Connect.
2. Repeat on 26.2.
3. Deny then approve an authenticated UUID and verify approval resets after
   restart.
4. Join from a vanilla offline-mode client through Connect, verify the host
   sees **Connect authenticated**, and verify the connection succeeds.
5. Join directly from a modded offline-mode client and verify the same
   per-connection approval rule.
6. Join automatically between two modded clients on one LAN with Connect
   unavailable.
7. Verify internet direct is never attempted without confirmation on both
   peers.
8. Verify successful internet direct where NAT permits it.
9. Verify a failed internet-direct attempt falls back to Connect.
10. Stop sharing and prove the hostname no longer reaches the world.
11. Start a different world and prove the same endpoint name and token are
   reused while the old signed invitation is rejected.
12. Import a dashboard-created endpoint and token, then prove its hostname and
    attached dashboard configuration are used without creating another
    endpoint.
13. Reject a bad imported token and prove the prior working identity remains
    intact.
14. Confirm no LAN/WAN Minecraft listener is reachable from another machine.

## Delivery Sequence

Implementation proceeds in independently testable slices without reducing the
final scope:

1. Kotlin/Fabric multi-version build, share state, admission, invitations, and
   version adapters.
2. Integrated-server local bridge and persistent Connect ingress for vanilla
   guests.
3. Same-LAN direct libp2p.
4. Opt-in internet direct attempts and Connect fallback.
5. Host/guest UI, packaging, release automation, and real-network acceptance.

Each slice follows test-first development and leaves both Fabric targets
buildable. Plugin release, mod release, and any production rollout remain
separate operations.
