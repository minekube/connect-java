# Connect Share Pasted LAN Invitation Design

**Date:** 2026-07-30
**Status:** Approved for implementation
**Parent design:** `2026-07-30-connect-share-mod-design.md`

## Problem

Connect Share advertises active modded hosts on the local network through
mDNS. The guest validates the signed invitation returned by the discovered
libp2p peer and stores its LAN multiaddress in `FabricShareBrowser`.

Selecting a nearby share in the join screen passes that multiaddress to
`FabricShareBrowser.join`, so the route planner tries direct LAN before
Connect. Pasting the same invitation clears the screen's selected LAN address.
The browser then plans with `sameLan = false`, skips direct LAN, and connects
through the public Connect endpoint even when the matching host is already
discovered nearby.

Live diagnosis confirmed that the host's mDNS advertisement, LAN TCP listener,
libp2p peer identity, and metadata protocol were reachable. The guest still
selected the public Connect hostname because the pasted-invitation path did
not associate the invitation with the matching discovery.

## Decision

`FabricShareBrowser` will reconcile a parsed invitation with its current
validated mDNS discoveries before planning routes.

When `join` receives no explicit LAN address, it will search the current
discovery snapshot for an entry whose signed invitation has both the same
`shareId` and the same `peerId` as the invitation being joined. A match
supplies the effective LAN address. Route planning then treats the peers as
same-LAN and preserves the existing order:

1. direct LAN;
2. direct internet, only when both peers opted in;
3. Minekube Connect.

An explicit LAN address from selecting a nearby-share button remains
authoritative. This keeps the current UI behavior while making paste, keyboard
paste, and programmatic join paths equally capable.

## Security and Privacy

LAN addresses remain outside copied invitations. They are local, transient,
and may reveal network topology if shared beyond the LAN.

Only validated discoveries are eligible for reconciliation. The existing
discovery path:

- dials the advertised libp2p peer;
- retrieves the invitation over the metadata protocol;
- verifies the invitation signature and expiry; and
- requires the invitation's `peerId` to equal the connected peer.

The additional `shareId` and `peerId` match prevents an unrelated nearby share
from influencing routing. The pasted invitation continues to supply the
capability used for tunnel authentication; no capability, endpoint token,
invitation URI, or LAN address is added to logs or error messages.

## Failure Behavior

Discovery is opportunistic. If the matching advertisement has not arrived,
has expired, or is unavailable, behavior remains unchanged: route planning
uses internet-direct candidates only when both peers opted in, then falls back
to Connect when a Connect address exists.

If the matched LAN address cannot be dialed, the existing direct failure
handling continues to the next planned route. The fix does not make mDNS or
direct P2P mandatory and does not weaken Connect fallback.

## Scope

The behavior belongs in `share/fabric-common` so Minecraft 1.21.11 and 26.2
receive the same fix without version-specific screen changes.

The implementation will modify:

- `FabricShareBrowser.join` to derive one effective LAN address from the
  explicit selection or a matching validated discovery; and
- `FabricShareBrowserTest` to cover pasted matching invitations and unrelated
  discoveries.

No invitation wire-format, mDNS protocol, libp2p protocol, Connect endpoint,
or Minecraft-version adapter changes are required.

## Acceptance Criteria

- Pasting an active same-LAN host's signed invitation while its matching mDNS
  discovery is present attempts `DIRECT_LAN` before Connect.
- The match requires both `shareId` and `peerId`.
- Selecting a nearby share explicitly continues to attempt `DIRECT_LAN`.
- An unrelated discovery never supplies a LAN address.
- Missing or failed LAN discovery preserves internet-direct and Connect
  fallback behavior.
- Tests pass for `share:fabric-common`, followed by the repository-wide
  `./gradlew build`.
- The rebuilt Fabric 26.2 mod is installed in both PrismLauncher test
  instances and a live join shows the guest connecting to a loopback proxy
  while the host records the session as direct LAN.
