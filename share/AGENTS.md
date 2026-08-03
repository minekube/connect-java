# Connect Share Kotlin Agent Instructions

These instructions apply to every file under `share/`.

## Arrow Is the Default Kotlin Toolkit

Connect Share uses [Arrow](https://github.com/arrow-kt/arrow) as the preferred
toolkit for functional domain modeling, typed errors, validation, concurrency,
resource safety, resilience, and immutable data transformations. Before writing
a custom abstraction in one of those areas, check Arrow's
[library reference](https://arrow-kt.io/learn/quickstart/libs/) and use the
Arrow equivalent when it fits.

Do not recreate capabilities Arrow already provides:

- Model expected domain failures with `Raise<E>` inside cohesive workflows and
  `Either<E, A>` at module or asynchronous boundaries. Reserve exceptions for
  defects, cancellation, and genuinely exceptional infrastructure failures.
- Use `ensure`, `ensureNotNull`, `zipOrAccumulate`, `mapOrAccumulate`, and
  `NonEmptyList` for parsing and validation instead of hand-written error
  collectors or fail-fast exception chains.
- Use `Option` when absence is part of the domain and must be explicit. Keep
  nullable values at Fabric, Minecraft, Java, JSON, or other interop edges, then
  convert them at the boundary.
- Use `resourceScope`, `Resource`, or Arrow AutoClose utilities for acquired
  tunnels, channels, embedded Connect runtimes, and other lifetimes that require
  ordered cleanup. Cancellation must never skip release.
- Use Arrow Fx Coroutines operators such as `parZip`, `parMap`, and race
  operators when they express intended structured concurrency more directly
  than custom coroutine orchestration.
- Use Arrow Resilience schedules, retry policies, and circuit breakers when the
  feature needs those behaviors; do not grow custom retry loops.
- Use Arrow Optics for repeated or deeply nested immutable updates instead of
  copy-chain helpers. Add the Optics/KSP dependency only once such updates exist.
- Use Arrow STM only when several pieces of concurrent state must change as one
  invariant-preserving transaction. Do not substitute it for a simple atomic or
  immutable state flow.
- Prefer Arrow's non-empty collections, combinators, and function utilities
  over equivalent local wrappers.

This is a preference for the appropriate Arrow abstraction, not a requirement
to wrap every Kotlin expression. Plain data classes, sealed interfaces,
collections, `when`, and structured coroutines remain idiomatic. Minecraft and
Fabric callback signatures stay native at their boundaries, and no Arrow type
may cross the Java Connect Core public API unless that API is deliberately
redesigned for Kotlin.

## Dependency Discipline

- Pin the stable Arrow stack version once in `Versions.arrowVersion` and import
  the `arrow-stack` BOM. Do not put independent Arrow versions in module builds.
- `share:common` exposes `arrow-core` because its typed outcomes are part of the
  Kotlin domain API. Runtime-specific modules keep additional Arrow libraries
  as implementation dependencies unless their types are intentionally public.
- Add an Arrow module when the code uses its capability. Do not add the entire
  Arrow ecosystem speculatively.
- Preserve coroutine cancellation. Never catch `CancellationException` as a
  typed domain error.

## Tests

- Assert both sides of typed outcomes and every accumulated validation error.
- For managed resources, test release on success, typed failure, exception, and
  cancellation.
- For retries or parallel operators, use deterministic virtual-time tests; no
  real sleeps.

## Prism Two-Client E2E

The E2E procedure has two owners; do not retell it here:

- The [connect-share-prism-e2e skill](../.agents/skills/connect-share-prism-e2e/SKILL.md)
  owns launching Prism identities, installing artifacts, running the opt-in
  `PrismFriendJoinE2ETest` harness (including its `LIVE_*` environment
  variables), keyboard-only visual QA and focus-order capture, and gate-by-gate
  failure diagnosis.
- [The testing guide](../docs/connect-share-testing.md) owns the acceptance
  matrix, evidence gates, `--rerun-tasks` rationale, identity-key cloning
  hazard, and manual Prism loader-component setup.

When a live run reveals a new stable rule, update the skill or guide and keep
only the operative invariant below.

## Share Invariants (pinned)

- `ShareConnectionGateway` installs Minecraft's captured Netty initializer
  after its accepted channel is already active; newly installed handlers must
  receive the active lifecycle before the first Minecraft bytes. Every path
  that installs the captured initializer runs on the loader's logical-server
  thread group - Forge derives packet side from the thread group, so a generic
  executor rejects login/play custom payloads as client-side. A directly bound
  local listener borrows Minecraft's captured `EventLoopGroup` and closes only
  its channel; the always-on Forge gateway owns loader-classified event loops
  supplied by the adapter. Pinned by
  `share/common/.../ShareConnectionGatewayTest`.
- Authenticated friend activity is the authority for visible online, playing,
  world-name, and joinable state. The gateway rejects raw Minecraft status
  whenever online, playing, or the current server/world name is hidden, and a
  capability route answering status must never promote an unknown or pending
  identity into social presence. Login remains independently admissible.
  Pinned by `ShareConnectionGatewayTest`.
- An integrated server object exists before its local player connection is
  ready. Publish only after both exist, and advertise `HOSTING_WORLD` only
  from an actual `ShareState.Sharing`.
- Admission resumes through a loader continuation: Fabric can call
  `handleAcceptedLogin` immediately, but Forge must enter its native
  `NEGOTIATING` state so FML login queries finish before play; skipping it
  makes Forge clients misclassify each other as vanilla.
- A direct session negotiated as `OFFLINE` must create Minecraft's standard
  offline profile in `handleHello`, before vanilla starts Mojang session
  authentication; `ONLINE` direct sessions must never silently downgrade.
- A `DirectP2pProxy` target is one-shot: a status probe consumes it, so open a
  separate target for gameplay and keep it alive until login finishes. Never
  reuse the friend-control target for a status probe or login.
- Keep the direct runtime on jvm-libp2p's tested Mplex default until another
  muxer passes both the Java 17 multi-window regression
  (`core/.../tunnel/p2p/DirectP2pNodeTest.java17PeersTransferAcrossManyMuxerWindows`)
  and every real-client adapter; Yamux on Netty 4.2 double-releases buffered
  window data on Java 17 after server login.
- The persistent social control peer and the active-world peer intentionally
  advertise the same stable share ID with different peer IDs. Discovery must
  retain one entry per `(shareId, peerId)` and refresh it when the same peer
  advertises a changed address; deduplicating by share ID alone can evict the
  saved friend's control route right after authenticated activity.
- Persistent friend cards retain signed direct candidates, tried after mDNS,
  never via Connect as a social relay. Copying a friend link is the disclosure
  boundary for those routes; removal revokes both admission grants and
  reciprocal-card proofs. Every copy action resolves the current handle
  invitation - renewing only the mDNS token leaves copies stale.
- `approveNextJoin` grants are one-shot admission capabilities, not durable
  friend state: expire within the admission timeout, deduplicate, bound by
  `maxPending`, and evict the oldest when full. A vanilla no-mod Connect join
  carries no signed direct-peer proof and may require ordinary pending
  admission even against a same-named auto-accept friend; never weaken
  UUID/peer matching. Automatic friend-card exchange requires a proof carrying
  a direct peer ID plus a signature-verified invitation naming that same peer;
  fail closed otherwise. Pinned by
  `share/common/.../admission/AdmissionControllerTest`.
- No-mod session admission must finish before vanilla's own connection
  timeout: keep a deadline buffer, cancel the pending host request on expiry,
  and encode an intentional denial as `PermissionDenied` with the safe copy
  repeated in a `google.rpc.LocalizedMessage` detail - Moxy never shows a
  connector-controlled raw status message, and a generic `Timed out` is a
  failed UX result.
- Recovery export/import runs only against the fixed Share allowlist and only
  while sharing is stopped; a backup target must never resolve to a live
  identity, friend, preference, endpoint, or transaction path. Validate and
  decrypt the entire archive before replacement, keep rollback material until
  a committed marker is durable, and never print archive paths, contents,
  passwords, identities, or tokens as evidence.
- Do not apply Shadow's generic `minimize()` to the isolated libp2p payload:
  jvm-libp2p reaches Kotlin, cryptography, protobuf, Noise, Guava, and Netty
  classes reflectively. Any payload-size reduction must keep cross-platform
  natives and be proved between two peers loaded from the exact packaged
  artifact. Legacy Forge's final reobfuscated JAR must contain its generated
  Mixin refmap named from the loader-specific mixin config, and Forge/NeoForge
  client resources need a compatible `pack.mcmeta`.
