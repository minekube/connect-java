# Connect Share marketplace and creator source kit

This is the source-of-truth copy and metadata for marketplace pages, modpacks,
and creator pilots. Visual assets and a final video are external launch
deliverables and must follow the storyboard below.

## Promise and short copy

**Tagline**

Install once. See your friends. Join whatever they are playing. No server setup.

**Short description**

Link with a friend once, see when their singleplayer world is ready, request to
join, and start playing. Connect Share tries direct peer-to-peer first and uses
Minekube Connect only when needed.

**Marketplace description**

Connect Share turns “my friend is playing” into playing together. Link once
with an authenticated friend identity. Later you can see privacy-controlled
online and joinable state, request access, and enter the active singleplayer
world without exchanging another IP or reopening sharing.

Direct libp2p is tried first, including after networks and IP addresses change.
Minekube Connect is the managed gameplay fallback when a direct route is not
available; nobody needs to run a relay. Ask Every Time is the default, with
per-friend Auto-Accept and Never Allow controls. Pending requests receive no
presence, and display names are never authorization.

The mod also detects obvious Minecraft, loader, and required-mod differences
before a late Minecraft failure. Offline-mode friends are supported without
silently downgrading an authenticated session. A host may offer an ordinary
Minecraft address to a friend without the mod after the Connect ingress path is
release-proven.

Connect Share is a focused universal party layer - not a cosmetics, chat, or
server-management suite.

## Supported release metadata

| Loader | Minecraft | Required install dependency |
|---|---|---|
| Fabric | 1.20.1, 1.21.1, 1.21.11, 26.2 | Fabric API and Fabric Language Kotlin |
| Forge | 1.20.1 | Kotlin for Forge installable `-all.jar` |
| NeoForge | 1.21.1 | Kotlin for Forge installable `-all.jar` |

Environment is client required, server optional. Artifact names follow
`connect-share-<loader>-<minecraft>-<release>.jar`. Marketplace relations are
required dependencies, not suggestions. Public/private modpack redistribution
is permitted under MIT when the license notice remains with the binary.

## Demonstration storyboard (maximum 30 seconds)

1. **0–4 s:** Two players, title-screen Friends card: “Robin is playing.”
2. **4–8 s:** One click on **Request**; caption: “No address. No server setup.”
3. **8–13 s:** Host receives the in-game request and chooses **Accept**.
4. **13–22 s:** Guest loads into the world; show both players together.
5. **22–27 s:** Privacy panel flashes Ask Every Time / Auto-Accept / Never
   Allow and direct-first / managed-fallback copy without network jargon.
6. **27–30 s:** Promise, marketplace badges, and exact supported matrix link.

Use captions and a silent-safe edit. Do not display endpoint names, invites,
addresses, peer IDs, usernames from real accounts, debug screens, or tokens.

## Required links and assets

- player/install/privacy guide: `docs/connect-share.md`;
- known issues: `docs/connect-share-known-issues.md`;
- security model: `docs/connect-share-threat-model.md`;
- source/reproducible build: this repository and the tagged GitHub Release;
- support: Minekube issue/Discord destinations selected for the launch cohort;
- changelog: the matching GitHub Release, never an unversioned download;
- checksums and GitHub artifact provenance from that release.

Final kit assets: square icon, marketplace banner, title/Friends/request/privacy
screenshots at readable scale, captioned demo source and export, transparent
logo, and light/dark press images. Every asset is reviewed for hidden names,
world data, addresses, or credentials before publication.
