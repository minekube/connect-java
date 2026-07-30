# Connect Share Direct P2P Implementation Plan

**Goal:** Complete the approved Connect Share scope with automatic same-LAN
mod-to-mod joins, explicitly opted-in internet-direct attempts, signed
invitations, and exactly-once Connect fallback.

**Architecture:** Keep the existing Minecraft listener bound to loopback. An
isolated child-loaded jvm-libp2p node advertises and discovers active shares
with mDNS, validates a signed versioned invitation/preface, and proxies the
resulting byte stream to the loopback Minecraft listener. The guest creates a
loopback-only proxy so vanilla Minecraft's client protocol remains unchanged.
Only JDK types and small immutable boundary records cross the reflective
classloader boundary.

**Policy invariants:**

- Same-LAN discovery and direct dialing are automatic when both players have
  the mod.
- Internet candidates are gathered and used only after explicit opt-in on both
  peers.
- No circuit-relay address is accepted or advertised by the direct runtime.
- Connect is the sole relay and the only fallback after a failed direct dial.
- Direct online authentication never downgrades to offline. Offline identity is
  visibly unverified and approved per connection.
- Peer identities, capabilities, invitations, and approvals are ephemeral per
  share. The Connect endpoint token remains the only persistent network secret.

## Task 1: Common invitation and route policy

- Add tests for signed invitation round-trip, tampering, expiry, version
  rejection, relay-address rejection, redaction, LAN-first ordering, dual
  internet opt-in, and exactly-once Connect fallback.
- Add Arrow-based invitation validation and transport selection models in
  `share/common`.
- Extend share options and state with direct-path status without exposing
  candidates or capabilities in `toString`.

## Task 2: Isolated libp2p host, discovery, and guest proxy

- Add failing Core tests for two loopback hosts exchanging a
  Minecraft-shaped stream, mDNS metadata resolution, ephemeral identities,
  signed invitation validation, and classloader boundary safety.
- Add parent-first JDK-only direct boundary types and a reflective
  `DirectP2pNode` facade.
- Implement the child-loaded runtime with Noise, Yamux, TCP, mDNS,
  versioned control frames, signed invitations, bounded timeouts, and no relay
  transport.
- Implement a host stream-to-loopback socket proxy and a guest loopback-only
  socket-to-stream proxy.

## Task 3: Host lifecycle and admission

- Add coordinator tests proving direct survives Connect failure, Connect
  survives direct failure, both are cleaned up, and no ingress yields `FAILED`.
- Add a `DirectShareIngress` resource to `ShareCoordinator` and report Connect,
  LAN, and internet statuses independently.
- Tag proxied direct sockets before Minecraft initializes login.
- Gate direct login after profile resolution. Reject an online request when
  Mojang authentication did not complete; treat explicit offline mode as
  unverified and approve it per connection.

## Task 4: Guest discovery, invitation join, and fallback

- Add a shared browser/join service with bounded LAN and internet timeouts.
- Start discovery when the multiplayer/Join Share UI is open and remove it on
  close.
- Add native Minecraft Join Share UI to both Fabric versions, including paste
  handling, path status, internet IP-disclosure confirmation, and actionable
  no-route errors.
- Route the successful local proxy address through each version's normal
  Minecraft connection screen.

## Task 5: Packaging, documentation, and verification

- Assert direct runtime classes remain inside the isolated payload and all
  public parent signatures reject isolated libp2p, Netty, Kotlin, and kotlinx
  types.
- Build and boot both exact Fabric targets.
- Update manual acceptance documentation and Epic #83 with implemented scope
  and the real-network checks still requiring two machines/live Connect.
- Run targeted tests, both mod builds, the broader Gradle build, artifact
  inspection, and a final diff/review pass.
