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

- Stable plugin artifacts are published from GitHub releases.
- The `release.yml` workflow uploads:
  `connect-spigot.jar`, `connect-velocity.jar`, `connect-bungee.jar`, and
  `LICENSE`.
- After creating a release, verify the release is not draft/prerelease unless
  intentionally so, and verify the asset digest/availability:

```sh
gh -R minekube/connect-java release view <version> --json tagName,targetCommitish,isDraft,isPrerelease,assets
curl -I -L --fail https://github.com/minekube/connect-java/releases/download/<version>/connect-velocity.jar
```

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
