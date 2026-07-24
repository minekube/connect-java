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

## Injector Scoping (config availability)

- The parent injector binds `ConfigHolder`; `ConnectPlatform.init()` populates it
  before `ConfigLoadedModule` binds `ConnectConfig` in the child injector.
- Anything reachable while constructing the parent/platform injector must avoid
  injecting `ConnectConfig` directly. Depend on the parent-bound `ConfigHolder`
  and read `configHolder.get()` lazily after initialization instead.
- The Bedrock identity graph follows this pattern; see
  `core/.../module/BedrockParentInjectorStartupTest` for the regression guard.

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

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
