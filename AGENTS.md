# Connect Java Agent Instructions

Connect Java builds the platform plugins used by Connect endpoints. Treat
plugin release, hub image rebuild, and production rollout as separate steps.

## Worktree Safety

- The root worktree may contain active libp2p or transport feature branches.
  Do not modify an active worktree unless explicitly asked.
- For unrelated fixes or release docs, create a separate worktree from
  `origin/connect`.
- Preserve user changes. Never reset or force-clean active branches.

## Release Flow

- Stable plugin artifacts are published from GitHub releases managed by
  `release-please.yml` on the `connect` branch.
- Use Conventional Commit prefixes to drive releases:
  `fix:` for patch, `feat:` for minor, and `feat!:`/`BREAKING CHANGE:` for
  major. Non-release prefixes such as `chore:`, `docs:`, `ci:`, and `test:`
  should not cut a stable release.
- `release-please.yml` opens and auto-merges a release PR, creates the version
  tag/release, then dispatches `release.yml` on that tag so the JAR artifacts
  are uploaded. Do not manually bump versions or create release tags unless
  repairing automation.
- Its release-PR build is a manual dispatch whose native matrix checks are
  audited on the captured head before merge. Do not mirror those checks into
  synthetic check runs or legacy statuses; the boundary is pinned by
  `core/.../release/ReleasePleaseCheckAuditTest`.
- The `release.yml` workflow uploads:
  `connect-spigot.jar`, `connect-velocity.jar`, `connect-bungee.jar`, and
  `LICENSE`.
- Pushes to `connect` still update the `latest-prerelease` release for
  unreleased testing builds.
- After creating a release, verify the release is not draft/prerelease unless
  intentionally so, and verify the asset digest/availability:

```sh
gh -R minekube/connect-java release view <version> --json tagName,targetCommitish,isDraft,isPrerelease,assets
curl -I -L --fail https://github.com/minekube/connect-java/releases/download/<version>/connect-velocity.jar
```

- `release.yml`'s "Verify published release assets" step re-reads each published
  release from the API (never the upload step's own output) and requires the
  same positive plugin-jar allowlist as `release-repair.yml`. Pinned by
  `core/.../release/ReleaseAssetVerificationTest`; keep that test's step and
  upload-step names in sync when editing `release.yml`.
- `release.yml`'s "Publish to Modrinth" step publishes the same jars to the
  Modrinth listing (project id `PuSyuNRf`, `minekube-connect`), one version per
  platform because Modrinth runs every validator whose loaders intersect the
  declared loaders against every file in a version. It uploads the runner's
  build output, never the release assets, and confirms each upload by reading
  the stored version back and comparing sha1 and sha512. Its event condition is
  the safety property: without it every push to `main` would publish a
  development build to a public listing without anything going red. Pinned by
  `core/.../release/ReleaseModrinthPublishTest`; keep that test's step names in
  sync when editing `release.yml`. Dispatching `release.yml` at an OLD tag
  publishes that tag to Modrinth - the listing is not a backfill target.
- `release.yml`'s "Publish to Hangar" step publishes to `minekube/Connect` and
  syncs `.github/hangar-description.md`. `HANGAR_API_TOKEN` needs
  `create_version` and `edit_page`. Hangar's platform mapping is Paper jar to
  `PAPER`, Velocity jar to `VELOCITY`, and Bungee jar to `WATERFALL` (Hangar
  has no BungeeCord platform). The step reads accepted platform versions at
  publish time, floors Paper from `plugin.yml` and Velocity at the existing
  3.0 compatibility boundary. The three shaded jars exceed Hangar's
  Cloudflare request limit as one multipart upload, so the version uses
  immutable versioned GitHub release URLs, stores their SHA-256 values in the
  public version description, and verifies GitHub's asset digest plus each
  Hangar download's final bytes, size, content type, and JAR magic. Pinned by
  `core/.../release/ReleaseHangarPublishTest`; keep its step names in sync.

### Repairing a release that published no assets

- Use `release-repair.yml` (default branch, manual dispatch). It builds at the
  JDK that tag's own `release.yml` pinned and uploads only missing or broken
  assets. Never dispatch `release.yml` at an old tag instead: it rewrites the
  live `latest` release, dragging the stable `releases/download/latest/*.jar`
  URLs backwards. Boundary and guards pinned by
  `core/.../release/ReleaseRepairCapabilityTest`; keep its step names in sync.
- A repair EXECUTES old, unreviewed tagged source. Never collapse the workflow's
  read-only `build` / write-only `publish` job boundary or substitute
  step-level token scoping; the workflow comments and
  `ReleaseRepairCapabilityTest` own the exact boundary, artifact-name
  allowlist, race handling, and landed-verification details. Top-level
  `permissions: {}` must remain explicit.
- Asset naming is per-era: tags up to 0.7.0 published version-suffixed jars
  (`connect-spigot-0.6.2.jar`), 0.7.1 onwards publish bare names. The repair
  derives which from the tag's own release workflow, so a repair does not rename.
- `0.6.0` and `0.7.0` are the only zero-asset releases and are **not**
  repairable. Their `bungee/build.gradle.kts` requests `bungeecord-proxy` with
  transitive deps, and `net.md-5:bungeecord-{api,log,protocol,query}` at
  `1.20-R0.3-SNAPSHOT` / `1.21-R0.1-SNAPSHOT` are 404 on every repository those
  tags declare - only `bungeecord-proxy` itself survives upstream. `main` avoids
  this with `includeTransitiveDeps = false`; back-porting that into a tag would
  change what the tag builds, so it is a rewrite, not a repair.

## Public integration contract for login/auth plugins

- The `connect-player` Netty channel attribute is a **permanent** public contract:
  once external login plugins depend on the name it may never be removed, renamed,
  or moved later in the connection lifecycle. Additive changes only.
- Authoritative sources: `api/.../api/ConnectAttributes.java` (key + Javadoc contract),
  the single set-site in `core/.../network/netty/LocalServerChannelWrapper.java`, and
  `docs/login-plugin-integration.md` (the integrator-facing doc and the stability
  commitment). Pinned by
  `core/.../network/netty/ConnectPlayerAttributeBoundaryTest`.

## Defensive login re-assert (proxy)

- Connect registers a **second, late** pre-login handler that restores its own decision after
  every other plugin has run where the platform supports strict-after ordering, fixing the whole
  "a login plugin forces online mode after Connect" class (LibreLogin, AuthMe, nLogin) with no
  runtime dependency on any of them. On older Velocity builds, the fallback is `PostOrder.LAST`
  plus the optional LibreLogin load-order edge, so other plugins retain the old last-writer
  behavior.
  Preserve the property
  that makes it safe: it reacts **only** to Connect's own result on the event object, never
  reads/links against/version-checks a third-party plugin, never overrides a deny, and no-ops
  when nothing changed the decision. The existing `EARLY`/`LOWEST` handlers stay as they are -
  this only adds a floor. If late-handler registration throws, `VelocityListenerRegistration`
  catches `Throwable` locally, logs the failure, and continues with the pre-existing behavior;
  ordinary listener registration remains unchanged. Authoritative:
  `velocity/.../listener/VelocityLateEventRegistrar.java` (the two layered ordering levers and
  why each exists), `VelocityLateReassertListener`, `BungeeLateReassertListener`,
  `core/src/main/resources/proxy-config.yml` (`login-reassert`), `docs/login-plugin-integration.md`.
- **Do not bump velocity-api past `3.2.0-SNAPSHOT`** to reach `PostOrder.CUSTOM`: Velocity reads
  the annotation while collecting a listener's methods, so an enum constant an older runtime
  lacks throws `EnumConstantNotPresentException` there and kills *all* of Connect's handlers on
  pre-2024-09-16 proxies, unguardably. The reflective short-`register` lookup buys the same
  ordering with a catchable failure.
- Default profile scope is properties-only (skin) deliberately; restoring Connect's UUID breaks
  login plugins that key their storage on the proxy UUID, so it is opt-in with its
  `new-uuid-creator: MOJANG` prerequisite documented next to the option.
- `:velocity:eventOrderTest` is a separate source set running the **real** `VelocityEventManager`
  and `PluginDependencyUtils` against a Velocity proxy jar pinned by sha256 (ivy repo in
  `settings.gradle.kts`; PaperMC publishes no proxy artifact to Maven). Separate because that
  shaded jar carries its own velocity-api and `com.velocitypowered.proxy` classes, which must not
  shadow the 3.2.0 API or the stubs in `velocity/src/test`. It runs under `check`, so a
  fill-data.papermc.io outage fails `./gradlew build` until the artifact resolves or is cached.

## Injector Scoping (config availability)

- The parent injector binds `ConfigHolder`; `ConnectPlatform.init()` populates it
  before `ConfigLoadedModule` binds `ConnectConfig` in the child injector.
- Anything reachable while constructing the parent/platform injector must avoid
  injecting `ConnectConfig` directly. Depend on the parent-bound `ConfigHolder`
  and read `configHolder.get()` lazily after initialization instead.
- The Bedrock identity graph follows this pattern; see
  `core/.../module/BedrockParentInjectorStartupTest` for the regression guard.

## DI annotations (Guice provider portability)

- Annotate injectable constructors/scopes/qualifiers with `com.google.inject.*`
  (`Inject`, `Singleton`, `name.Named`) — the codebase standard. Never use
  `javax.inject.*`: Velocity ships Guice as a `provided` runtime and the plugin
  builds a child of Velocity's own injector (`VelocityPlugin`), so it runs on the
  platform's Guice. Velocity 4.0.0 provides Guice 7, which dropped `javax.inject`
  support (recognizes only `com.google.inject`/`jakarta.inject`), so a
  `javax.inject`-annotated class is unprovisionable there ("Cant create plugin
  connect"), while Spigot/Bungee (shaded Guice 6) and Velocity 3.x (Guice 5) still
  accept `javax`. Guarded by
  `core/.../bedrock/BedrockVelocityGuice7ProvisioningTest`.

## libp2p Runtime Isolation (reflective boundary)

- The parent-facing wrappers `Libp2pEndpoint` and `Libp2pTunnelTransport` load
  their isolated runtimes through `Libp2pRuntimeLoader.classLoader()` and resolve
  the runtime constructor/methods reflectively by exact signature
  (`getDeclaredConstructor(...)`). This boundary is not compile-time checked.
- When an isolated runtime's constructor changes (e.g. new injected deps), update
  the wrapper's reflective lookup AND its `newInstance(...)` in lock-step, and add
  the new deps to the wrapper's `@Inject` constructor so Guice provides them.
- Signature drift is a runtime initialization failure, not an upstream
  jvm-libp2p or JDK compatibility issue.
- Only parent-loaded types (e.g. `com.minekube.connect.bedrock.*`, api/config
  types) may cross this boundary as parameter types; child-first prefixes
  (`io.libp2p.*`, `io.netty.*`, `kotlin*`) must not appear in wrapper signatures.
  `Libp2pRuntimeLoader` is the authoritative implementation; the boundary is
  guarded by `Libp2pRuntimeBoundaryTest`, and constructor alignment by
  `core/.../tunnel/p2p/Libp2pEndpointRuntimeInitTest`.

## Third-party platform APIs (ViaVersion & friends)

- Connect runs against a wide range of server/plugin versions, so an API that
  drifts across majors must not be bound at compile time. Resolve cross-version
  accessors reflectively by name (newest first) and degrade to skipping the
  workaround with a warning when no known accessor exists; see
  `SpigotInjector#unwrapViaInitializer` and `SpigotInjectorViaLegacyPathTest`.
- Only plain Spigot/CraftBukkit takes Via's wrapping (legacy) injector path. On
  Paper `BukkitViaInjector` registers a `ChannelInitializeListener` instead, which
  Connect's local channel picks up for free — so Paper never exercises the unwrap.
- Injector failures must stay diagnosable: `ConnectPlatform.enable()` catches
  `Throwable` (not `Exception`) because reflective signature drift arrives as an
  `Error`, making it a logged, orderly injection failure rather than an unhandled
  `Error` escaping `onEnable()`. `SpigotPlatform.enable()` still disables the plugin
  on a false return, so the value is the diagnosable log line, not continued operation.
  Guarded by `core/.../ConnectPlatformEnableFailureContainmentTest`.
- Compile-only platform deps live in `build-logic/.../Versions.kt` and are excluded
  from the shaded jar by `provided(...)`; the `viaversion-bukkit` artifact declares
  no transitive deps, so `viaversion-common` must be requested explicitly.
- Spigot NMS drift is expected when a Minecraft release renames an internal accessor:
  `spigot/.../util/ClassNames.java` resolves server internals in one static initializer,
  and failures are latched in the separate `NmsDiagnostics` class so they remain
  available after `ClassNames` becomes erroneous. `SpigotPlatform.enable()` logs the
  accessor and environment from that latch. Route new lookups through the
  `NmsDiagnostics` helpers so they stay reportable; `spigot/.../util/NmsDiagnosticsTest`
  guards this contract.

## Bedrock signed-principal v2

- The Bedrock signed-principal v2 contract is additive to v1. Authoritative Java surfaces are
  `api/.../player/principal`, `core/.../bedrock/BedrockPrincipalConsumer`, the Watch/libp2p
  protos and registration framing, and `core/src/test/resources/bedrock-principal-v2/UPSTREAM`.
  Keep principals verifier-constructed/sealed, consume envelopes only from authenticated wire
  field 12, preserve generation-1 `warn` files byte-for-byte, and never advertise v2 readiness
  without exact generation-2 `require`, usable keys, replay, and profile application.

## Java runtime compatibility

- Java 26 is not a libp2p classloader incompatibility; the isolation has been verified on
  Java 26. Treat a Java 26 report as an NMS compatibility signal until the
  `NmsDiagnostics` line identifies the server and Minecraft version.
- CI currently stops at JDK 21 because the repository uses Gradle 8.5; the authoritative
  matrix is `.github/workflows/pullrequest.yml`.

## Velocity Join Bugs

- For Velocity proxy issues, test both `CONFIGURATION` and `PLAY` state packet
  handling. Reconfiguration packets can arrive before normal play state.
- Keep Connect's Netty/runtime isolation intact. Avoid reusing server pipeline
  state across the connector runtime without a focused test.
- If a hub uses this plugin, update the hub's `velocity/deps.env`, rebuild the
  hub image, deploy through gitops, and verify a real public join.

## Verification

Run targeted module tests for packet/session fixes, then the broader build:

```sh
./gradlew :velocity:test
./gradlew build
```

Do not call production fixed from a Connect Java release alone. Confirm the
released jar is in the hub image, the hub pod logs the expected plugin version,
and Moxy accepts a tunnel with the same `connectorVersion`.

### Per-platform startup/DI regression guard

- Each platform (Velocity/Spigot/Bungee) has a plugin startup smoke test
  (`<platform>/src/test/.../<Platform>PluginStartupTest`) plus core
  `startup/PluginGraphStartupTest`, sharing the `core` test fixture
  `core/src/testFixtures/.../startup/StartupGraphProvisioning`: it walks the real
  `@Inject` graph and replays Guice 7's injectable-constructor rule, so a provider
  made unprovisionable on any platform's injector (the Velocity 4 / Guice 7 class
  of bug) fails the suite. The Velocity test fails on the pre-fix `javax.inject`
  annotations and passes on the fix. Add new platform DI classes to that
  platform test's `*GraphRoots()`.
- `pullrequest.yml` runs `./gradlew build` on a JDK matrix (17, 21); the
  Java-26-class reflective bugs (Guice 7 DI, `Libp2pEndpointRuntime` ctor arity)
  are guarded by signature-level tests independent of the running JDK.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
