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
  **Privacy**. When a friend is on another server, **Show current server** can
  also hide that server's name; the current singleplayer world name remains
  visible while hosting.
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

Forge and NeoForge reuse the loader-neutral Kotlin core and version-specific
Minecraft UI/bridge adapters. Use the exact packaged artifact under test for
the real two-client Prism acceptance pass in
[the testing guide](connect-share-testing.md).
