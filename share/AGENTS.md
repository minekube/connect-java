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

- Prism can drive the live flow without UI automation. Launch the host with
  `prismlauncher --launch <instance> --profile <account> --world <world>` and a
  distinct offline guest with
  `prismlauncher --launch <instance> --offline <name> --server <host:port>`.
  `--offline <name>` is authoritative; editing `InstanceAccountId` while Prism
  runs is not, because Prism rewrites it.
- Prove the flow in layers: mDNS discovery, authenticated friend activity,
  Minecraft status when host privacy permits it, then a real login whose host log contains
  `<name> joined the game`. Control-plane reachability or a status response does
  not prove that the world is joinable. `dns-sd -B
  _minekube-connect-share._tcp local` and `jcmd <pid> GC.class_histogram` are
  useful diagnostics for discovery and live `ShareState`/transport objects.
- Run only one Gradle invocation at a time in a worktree. Concurrent test tasks
  share `build/test-results` and can delete one another's in-progress binary
  results, producing a false infrastructure failure.
- A `DirectP2pProxy` target is currently one-shot. A status probe consumes it;
  open a separate target for gameplay and keep that target alive until the
  Minecraft connection finishes. Never reuse the friend-control target for a
  status probe or login.
- Authenticated friend activity is the authority for visible online, playing,
  world-name, and joinable state. Never promote raw Minecraft status into UI
  presence without a matching privacy-filtered activity response. The gateway
  rejects status whenever online, playing, or the current server/world name is
  hidden. A capability route may answer status only when all three are visible;
  this must never promote an unknown or pending identity into social presence.
  Login remains independently admissible so a privacy-safe join request can
  still succeed.
- An integrated server object exists before its local player connection is
  ready. Publish only after both exist, and advertise `HOSTING_WORLD` only from
  an actual `ShareState.Sharing`; otherwise friends see a world that cannot yet
  accept them.
- `ShareConnectionGateway` installs Minecraft's captured Netty initializer
  after its accepted channel is already active. Any change to that dispatch
  must preserve a focused test proving newly installed handlers receive the
  required active lifecycle before the first Minecraft bytes.
- A direct session negotiated as `OFFLINE` must create Minecraft's standard
  offline profile in `handleHello`, before vanilla starts Mojang session
  authentication. Otherwise an offline Prism friend is rejected as "Invalid
  session" before admission runs. `ONLINE` direct sessions must never silently
  downgrade.
- Persistent friend cards must retain signed direct candidates and friend
  control must try those candidates after mDNS, without ever using Connect as a
  social relay. Copying a friend link is the disclosure boundary for those
  routes; removal must revoke both admission grants and reciprocal-card proofs.
- Invitation renewal is not complete when only mDNS receives a fresh token.
  Every copy action must resolve the current handle invitation so a long-running
  share never copies the original expired token.
- For no-click friend-request E2E, temporarily enable automatic joins only for
  the confirmed test friend, send the real libp2p join request, and restore the
  permission afterwards. Keep machine-specific instance paths and credentials
  in environment variables, never in committed tests or scripts.
- `PrismFriendJoinE2ETest` is the opt-in live harness. Start the host first,
  supply `LIVE_DATA`, `LIVE_PORT_FILE`, and `LIVE_HOST_LOG`, then launch the
  guest against the port written to `LIVE_PORT_FILE`. The test succeeds only
  after the host logs a new `<name> joined the game` line.
- Invoke the live harness with `--rerun-tasks`. Its environment variables are
  intentionally not task inputs, so an up-to-date result is not live evidence.
- Keep only one host and one guest identity active during a live run. Cloning a
  Prism instance copies `share-libp2p-identity.key`; simultaneously advertising
  that same peer identity from several processes makes mDNS routing ambiguous
  and can produce misleading libp2p stream failures.
- Manually constructed Prism Forge/NeoForge components need correct
  `cachedRequires` metadata and usually one online first launch to download
  loader libraries. Kotlin for Forge must be installed from its `-all.jar`;
  the smaller Maven compile artifact is not a discoverable loader mod.
- Legacy Forge's final reobfuscated JAR must contain its generated Mixin refmap
  and name it from the loader-specific mixin config. Forge and NeoForge client
  resources need a compatible `pack.mcmeta`, otherwise startup can stop at a
  resource-pack warning before quick-play E2E begins.
- Visual QA is keyboard-only at both the normal Prism window size and 640x400.
  A focused Minecraft `EditBox` hides its hint, so every input needs a
  persistent label; split pause-menu buttons must keep copy within their
  100-pixel logical width. The repository Prism skill owns the capture and
  focus-order procedure.
