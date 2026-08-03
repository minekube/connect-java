---
name: connect-share-prism-e2e
description: Drive and diagnose Connect Share with two real Prism Launcher clients. Use for installing a local Connect Share Fabric build, launching distinct host and guest identities, verifying confirmed-friend presence and singleplayer join approval, testing libp2p-direct versus Connect fallback routes, debugging Minecraft status or login failures, or preserving new reusable Connect Share E2E knowledge.
---

# Connect Share Prism E2E

Use the repository's opt-in live harness to prove the complete friend-to-world
flow. Treat discovery, activity, privacy-permitted status, approval, and
Minecraft login as separate gates; success at an earlier gate never proves a
later one.

The commands below use Fabric 26.2 as the reference target. For another
supported loader/version artifact, preserve the same evidence gates and follow
`docs/connect-share-testing.md` for the complete matrix and loader-specific
packaging steps.

## Prepare safely

1. Read the root `AGENTS.md` and `share/AGENTS.md` completely.
2. Work in the active isolated Connect Share worktree. Never modify a separate
   active/root worktree or discard user changes.
3. Inspect the current branch, diff, Prism instances, saved friend stores, and
   running Minecraft processes before relying on earlier session notes.
4. Run only one Gradle invocation in a worktree at a time. Concurrent test tasks
   corrupt their shared `build/test-results` state.
5. Keep profile paths, endpoint tokens, capabilities, account identifiers, and
   friend cards out of committed files and tool summaries.

## Build and install

Build the current 26.2 artifact:

```sh
./gradlew :share:fabric-26-2:connectShareJar --no-parallel
```

Locate the final unclassified JAR under `share/fabric-26.2/build/libs/`. Install
that exact artifact into both instances' `minecraft/mods/` directories. Remove
or replace older Connect Share JARs so each instance loads exactly one. Compare
SHA-256 digests for the build output and both installed copies.

Match replacement JARs by basename at the immediate `mods/` level. Never run a
`connect-share-*.jar` regex against the full absolute path: these test instance
directory names also contain `connect-share`, so a greedy match can remove
Fabric API, Fabric Language Kotlin, or Kotlin for Forge along with the mod.

Confirm each fresh `latest.log` contains both Fabric Loader startup and a
`connect-share` mod entry. Fabric Language Kotlin is declared as a mod
dependency; do not infer a successful load merely from the file being present.

## Launch the two identities

Use Prism's command-line controls; do not automate Minecraft UI clicks:

```sh
prismlauncher --launch <host-instance> --profile <host-account> \
  --world <world-name> --show-window

prismlauncher --launch <guest-instance> --offline <guest-name> \
  --server 127.0.0.1:<e2e-port> --show-window
```

`--offline <name>` is authoritative for the guest. Do not edit
`InstanceAccountId` while Prism is running because Prism rewrites it.

Wait until the host log records its local player joining and `Connect Share
friend gateway is ready`. The integrated server object exists before the local
client connection is ready; the mod must publish only when both exist and must
advertise `HOSTING_WORLD` only from an actual `ShareState.Sharing`.

Do not treat a matching JVM PID as launch success. Snapshot `latest.log` before
launch and require both a newer mtime and the expected world/runtime markers.
Prism can otherwise leave an old JVM occupying the instance while its log no
longer advances. Resolve exactly one process by the instance's working
directory before stopping it; never terminate Java processes by name alone.

## Run the opt-in live harness

The executable harness is
`share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/PrismFriendJoinE2ETest.kt`.
Start it after the host world is ready:

```sh
LIVE_DATA=<guest-config/minekube-connect-share> \
LIVE_TARGET_FILE=<fresh-temporary-target-file> \
LIVE_HOST_LOG=<host-minecraft/logs/latest.log> \
LIVE_GUEST_LOG=<guest-minecraft/logs/latest.log> \
LIVE_PLAYER_NAME=<guest-name> \
./gradlew :share:fabric-common:test \
  --tests '*PrismFriendJoinE2ETest*' --rerun-tasks --no-parallel
```

The harness keeps its pre-launch log snapshot immutable across Prism's
`latest.log` rotation. Keep `LIVE_GUEST_LOG` pointed at that active path: a new
or replaced log containing an advancement line is post-launch evidence and
must not be absorbed into a later baseline before the poll observes it.

The test must remain running while the external guest uses the join target
written to `LIVE_TARGET_FILE` (`LIVE_PORT_FILE` remains a direct-only
compatibility alias). It proves, in order:

1. mDNS discovers the saved confirmed friend's peer identity.
2. Authenticated friend control reports `HOSTING_WORLD`.
3. When the host exposes its world name, a dedicated direct proxy answers a
   real Minecraft status probe; otherwise privacy-filtered activity remains the
   authority and raw status is intentionally skipped.
4. The libp2p friend join request reaches the host and is approved.
5. A fresh gameplay proxy is opened.
6. A real guest login causes a new `<guest> joined the game` host-log line and
   a new `Loaded ... advancements` guest-log line before the gameplay proxy is
   released.

The current `DirectP2pProxy` is one-shot. A status probe consumes its target;
always use a different proxy for gameplay and keep the gameplay target alive
until login completes.

## Verify the player-facing UX

Treat visual QA as a keyboard-only Prism test, not as a source review:

1. Open every Connect Share state with Tab, Shift-Tab, Enter, and Escape. Widget
   insertion order is Minecraft's focus order, so verify both directions and
   keep the primary action reachable before secondary or destructive actions.
2. Capture the Minecraft window at its normal size, then resize it to 640x400
   points and capture the same dense states again. On macOS, read the Java
   window's position and size through System Events, then pass those point
   coordinates to `screencapture -R`; Retina output is expected to have twice
   the pixel dimensions.
3. Inspect title, pause, Friends, add-link, manage, Privacy, setup (collapsed and
   expanded), active status, compatibility, blocked-list, and endpoint states.
   Require visible hierarchy, non-overlapping footers, readable translated
   copy, consistent Back/Escape behavior, and exactly one obvious primary
   action.
4. Give every `EditBox` a persistent nearby label. Minecraft hides an empty
   field's hint while the field is focused, so a hint alone becomes a blank
   white rectangle during the most important input moment.
5. Keep a split vanilla pause-menu row at 100 + 4 + 100 logical pixels and use
   short labels that fit each half. Keep title-menu affordances compact and
   live-update request/readiness counts without covering the panorama.
6. Exercise Manage → Back → Add from link. The new-request form must be empty
   and use safe connection defaults; an existing friend's name or advanced
   route choices must never leak into another relationship draft. The one
   exception is Add → Connection options → Done, which preserves the active
   draft. On the pause menu, tab order must reach Share and Friends before Save
   and Quit even though the vanilla disconnect button was created first.

Screenshot appearance is evidence, not a golden test. Keep deterministic
layout and presentation decisions in pure Kotlin tests so visual fixes remain
portable across every loader and supported Minecraft API.

For no-click automation, temporarily enable automatic joining only for the
already confirmed test friend. Restore `canJoinAutomatically` to `false` and
restart the host after the run. A deterministic test must separately cover the
normal pending request, host approval, and one-shot admission path.

A vanilla no-mod Connect client does not carry the signed direct peer proof
used by the friend-control path. If its authenticated Connect UUID does not
match the stored offline friend UUID, auto-accept must fail closed and create a
normal pending admission. Do not relax that security boundary for automation.
For an unattended local proof, a temporary uncommitted attach driver may call
the installed `ShareViewModel` only after asserting exactly one pending request,
then invoke the existing `allow` action. Report only boolean/stage results;
never print the identity or request ID, never add a production test bypass, and
delete the driver after restoring the original policy.

## Diagnose by gate

- **Mod load:** inspect both fresh logs for the exact version and startup error.
- **Discovery:** use `dns-sd -B _minekube-connect-share._tcp local`; expect both
  persistent peer IDs. mDNS presence does not prove friend authentication.
  Apply the route-retention and mDNS-refresh invariant in `share/AGENTS.md`
  before interpreting discovery order or address changes.
- **Runtime readiness:** use `jcmd <host-pid> GC.class_histogram` to look for
  `ShareState$Sharing`, `ActiveTransport`, `PublishedVanillaTransport`, and
  `ShareCoordinator$ActiveShare` when ordinary logs are insufficient.
- **Activity/privacy:** query through the saved friend relationship. Pending or
  unknown peers must not receive presence or world details.
- **Status:** open its own target only when the host exposes online, playing,
  and current-world details. The gateway intentionally closes status otherwise;
  use authenticated activity plus a real approved login as the privacy-safe
  proof. A Connect endpoint fallback status or public DNS response does not
  prove the integrated world is reachable.
- **Login:** require both a guest `Loaded ... advancements` line and a host
  `<guest> joined the game` line.

Recognize these established failure signatures:

- Publishing from only `hasSingleplayerServer()` can race a null Minecraft
  client connection. Require the integrated server and client connection.
- Installing Minecraft's captured Netty initializer after socket activation
  requires replaying `channelActive` to the late handlers before the first
  Minecraft bytes. Keep the focused gateway lifecycle test.
- `Invalid session` for an explicitly offline libp2p guest means vanilla Mojang
  authentication ran too early. Create Minecraft's standard offline profile in
  `handleHello`; never downgrade an `ONLINE` direct session.
- A Java 17 guest that reaches server login, immediately disconnects, and makes
  the host flood Yamux `IllegalReferenceCountException`/freed-buffer warnings
  has crossed every control-plane gate but failed gameplay transport. Preserve
  the multi-window `DirectP2pNodeTest` regression and use jvm-libp2p's tested
  Mplex default; do not retry or hide the post-login disconnect.
- A Forge guest that reaches login and receives `Unexpected custom data from
  client` or `Illegal packet received, terminating connection` without muxer
  errors has failed loader negotiation or thread-side classification. The
  always-on Share gateway must run Minecraft handlers on a Forge
  logical-server thread group, and approved offline admission must enter
  Forge's native `NEGOTIATING` state instead of calling accepted-login early.
  A passing proof logs the guest's modded-server handshake, server join, and
  guest advancements with none of those rejection lines.
- A host `lost connection: Disconnected` line alone is incomplete evidence.
  Inspect the guest log or screen and whether the owner of the one-shot proxy
  closed it.
- A vanilla Connect guest showing only `Timed out` after the host approval
  window means the control-plane admission deadline collided with Minecraft's
  own connection timeout. Keep the Connect session decision shorter than the
  vanilla deadline, cancel its pending admission when that budget expires, and
  require the guest log to contain the actionable denial rather than treating
  generic timeout as acceptable evidence.

For recovery product proof, stop sharing and close the source profile before
export/import. Use disposable profile copies, compare only expected file hashes
or redacted relationship counts, and never print the backup path, password,
archive bytes, private identities, friend capabilities, or endpoint token.
Verify wrong-password and one-byte-damaged imports leave every live allowlisted
file hash unchanged. Never launch the source and restored copy simultaneously:
an offline backup transfers one stable identity and is not multi-device sync.

## Finish and retain knowledge

Run focused regression tests first, then:

```sh
./gradlew clean build --no-parallel
```

Before claiming completion, confirm the worktree is clean or intentionally
changed, installed JAR digests match, temporary auto-approval is restored, and
both intended Prism profiles are in a safe state.

When a live run reveals a stable, non-obvious rule, update this skill and the
appropriate concise invariant in `share/AGENTS.md`. Record commands, gates,
failure signatures, and authoritative files - not transient PIDs, ports, local
absolute paths, endpoint secrets, or raw debugging noise.
