# Connect Share

Connect Share is a private friend and party layer for Minecraft Java. Link with
a friend once, then see when they are playing, ask to join a shared world, or
follow them into their next joinable session. Players do not need to exchange
IP addresses or create a new link for every world.

## The normal flow

1. Open **Friends** from the title screen and copy your friend link.
2. Send it to the person you know. Adding the link sends a request; it does not
   reveal presence or make either player a confirmed friend yet.
3. The other player accepts the request. Reciprocal requests converge into the
   same confirmed friendship.
4. When a confirmed friend shares a singleplayer world, choose **Request**.
   The host gets an in-game notification and can allow or deny it.
5. Connect Share tries a direct libp2p path first. If that is unavailable, the
   approved gameplay connection falls back to Minekube Connect. Friend
   requests and presence themselves are authenticated libp2p traffic and never
   use Connect as a social relay.

Friend links carry signed direct candidates from the always-on social libp2p
path when the local host has a usable internet route. This lets the social
plane reach a friend outside the LAN without Connect; copying and sending the
link is the explicit disclosure of that route. The mDNS advertisement contains
only local discovery metadata; it never publishes public candidates,
capabilities, or endpoint tokens. A reciprocal card exchange refreshes saved
candidates when friends reconnect from a new network. No circuit relay is
accepted.

**Follow next session** waits for one friend for up to 30 minutes. It sends at
most one request for a world session, can be cancelled from the Friends screen,
and never pulls the follower out of active gameplay. Automatic admission still
requires the host to select **Auto-Accept** for that specific friend.

## Friends without the mod

While a world is shared, **Copy server address** copies an ordinary
`*.play.minekube.net` address. A vanilla client can paste it into Minecraft's
Direct Connect screen. The host still approves the player and the configured
guest limit still applies. The same endpoint identity and token are reused
across worlds and restarts, so switching worlds does not create endpoint spam.

The address is unavailable when the host has no working Connect path. An
approval is temporary: denial, timeout, capacity, stopping the share, removal,
or blocking cannot be bypassed with an old attempt.

## Privacy and safety

- Only confirmed peer identities receive presence. Display names are labels,
  never identity or authorization.
- Online, playing, and joinable state can each be hidden independently under
  **Privacy**. **Show current server or world** hides both multiplayer server
  names and singleplayer world names. Raw Minecraft status is not treated as
  social presence: a capability-authenticated route can query it only when
  online, playing, and current-world visibility are all enabled. The Friends
  UI still requires a confirmed, privacy-filtered activity response.
- Each friend can be set to **Ask Every Time**, **Auto-Accept**, or **Never
  Allow**. The default is Ask Every Time.
- Removing a friend revokes future presence and admissions and is synchronized
  when the peer is reachable. Blocking also prevents the identity from being
  added again until explicitly unblocked.
- Internet-direct gameplay remains opt-in on both sides: the host enables
  **Allow faster direct internet connections** for the shared world, and the
  guest separately enables **Allow direct internet routes for this friend** in
  that friend's **Manage** screen. The guest choice is off by default and is
  persisted per friend, so background friend activity checks use it without
  asking again. A copied friend link may contain signed direct candidates so
  the recipient can deliver the friend request without Connect; only send it
  to someone you trust. Direct addresses, endpoint tokens, invitation
  capabilities, and private keys are never rendered in the social UI.
- **Copy safe diagnostics** is an explicit, local action. Its report contains
  version and join-stage outcomes, but no names, addresses, links, tokens, or
  keys.

Compatibility exchange is peer-to-peer and limited to confirmed friends. It
contains Minecraft version, loader, a normalized list of server-relevant mod
identifiers and versions, and an optional HTTPS modpack link configured by the
host. It is not uploaded to Minekube. Client-only differences may be overridden;
Minecraft or loader differences cannot.

## Backing up friends and identity

Open **Privacy**, then **Backup & restore**. **Back up friends** creates one
offline file protected by the recovery password you enter twice. It contains
the social and gameplay identities that let existing friends recognize you,
saved relationships, access identity, preferences when present, and the local
Connect endpoint configuration and token when both are present. The file is
encrypted and integrity checked before it is written; neither the file nor its
password is sent to Minekube.

Keep the backup and its password separately. The password cannot be recovered,
and anyone who has both can act as this Share identity. **Restore backup** first
authenticates the complete file and shows a content-category summary. A second
confirmation then atomically replaces this device's Share data. Stop sharing
before restoring and restart Minecraft afterward. A wrong password, damaged
file, unsupported version, interrupted write, or failed replacement leaves the
current installation unchanged or rolls it back.

A restored backup is a device transfer, not multi-device synchronization. Do
not run two copied profiles at the same time: they hold the same identity and
can race presence or friend operations. If the old device was lost without a
backup, create a new identity and have friends verify and add it again; removing
or blocking the old relationship remains the revocation mechanism. Automatic
cross-device enrollment, remote revocation, and conflict-free simultaneous
devices require a future recovery protocol and are not provided by the offline
archive.

The existing **Connect endpoint** token-file import is a separate operation. It
imports credentials downloaded from the Minekube dashboard and does not restore
friends or the Share social identity. Conversely, the recovery screen never
accepts a dashboard token as a recovery password or friend backup.

## Installation and distribution

Supported artifacts are named
`connect-share-<loader>-<minecraft>-<release>.jar`. The current matrix is
Fabric 1.20.1, 1.21.1, 1.21.11, and 26.2; Forge 1.20.1; and NeoForge 1.21.1.
Install the artifact matching both the exact Minecraft version and loader.

Fabric builds require Fabric API and Fabric Language Kotlin. Forge and
NeoForge builds require Kotlin for Forge. For a manual Forge/NeoForge install,
download Kotlin for Forge's installable `-all.jar`; the smaller Maven library
JAR is not a loader mod. Modrinth and CurseForge releases declare these as
required dependencies so their apps and Prism can resolve them automatically.

The MIT license explicitly permits including Connect Share in public or private
modpacks. Keep its license notice with redistributed binaries. Verified release
artifacts are staged by the manual **Release Connect Share** workflow for
GitHub Releases, Modrinth, and CurseForge only after all six adapter builds,
packaging tests, isolation checks, and artifact-size gates pass. Marketplace
publication additionally requires the repository's project IDs and publisher
credentials; the workflow fails closed when they are absent.

The release workflow also creates GitHub/Sigstore build-provenance
attestations for every JAR and checksum manifest and verifies them before the
workflow succeeds. Public launch additionally follows the
[operations](connect-share-operations.md),
[threat model](connect-share-threat-model.md), and
[staged launch](connect-share-launch.md) gates. An HTTPS invite is deliberately
not emitted until the separately hosted
[handoff contract](connect-share-handoff.md) is deployed and verified.

Forge and NeoForge reuse the loader-neutral Kotlin core and version-specific
Minecraft UI/bridge adapters. Use the exact packaged artifact under test for
the real two-client Prism acceptance pass in
[the testing guide](connect-share-testing.md).
