[![Logo](https://github.com/minekube/connect/blob/b599dfc8e37741922f4cbfb8f6c1c6ec36ee742d/.web/docs/public/og-image.png?raw=true)](https://connect.minekube.com)

# Minekube Connect - Plugin

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Build Status](https://ci.opencollab.dev/job/GeyserMC/job/Floodgate/job/master/badge/icon)](https://ci.opencollab.dev/job/GeyserMC/job/Floodgate/job/master/)
[![Discord](https://img.shields.io/discord/633708750032863232.svg?color=%237289da&label=discord)](https://minekube.com/discord)
[![HitCount](https://hits.dwyl.com/minekube/connect-java.svg)](http://hits.dwyl.com/minekube/connect-java)

Minekube Connect allows you to connect any Minecraft server, whether online mode, public, behind
your protected home network or anywhere else in the world, with our highly available, performant and
low latency edge proxies network nearest to you.

Please refer to https://connect.minekube.com for more documentation.

## Connect Share mod

Connect Share is an in-development client-side Fabric, Forge, and NeoForge mod.
It supports Fabric 1.20.1, 1.21.1, 1.21.11, and 26.2; Forge 1.20.1; and
NeoForge 1.21.1. It shares a singleplayer world through Minekube Connect or
directly between two modded clients without exposing Minecraft's listener to
the LAN or internet.

The current implementation provides:

- a native **Share with friends** flow in the pause menu;
- a native **Friends** flow on the title screen, including **Join Connect Share**;
- one persistent endpoint identity reused across worlds and restarts;
- one authenticated libp2p friend identity, with presence and world details
  visible only to confirmed friends;
- import of an existing dashboard endpoint and token, including `token.json`;
- `CONNECT_ENDPOINT` and `CONNECT_TOKEN` environment overrides;
- a stable `*.play.minekube.net` address for unmodified Java clients;
- signed friend links and temporary world invitations for modded clients;
- automatic same-LAN discovery and direct libp2p transport;
- optional internet-direct attempts only when host and guest both opt in;
- exactly-once fallback to Connect, which is the only relay;
- host approval before each new guest reaches the world;
- explicit support for authenticated and unverified offline-mode guests; and
- compatibility checks before a friend requests access;
- follow-next-session intents that never interrupt active gameplay; and
- isolated, version-and-loader-labelled artifacts for every supported target.

Fabric builds require Fabric API and Fabric Language Kotlin. Forge and NeoForge
builds require the installable Kotlin for Forge `-all.jar`. Marketplace release
metadata declares the matching dependencies so compatible launchers, including
Prism, can install them automatically. Connect Share is MIT licensed and may be
included in modpacks without asking for additional permission. See
[the player, privacy, and distribution guide](docs/connect-share.md).

The mod artifacts have their own build and acceptance process. They are not part
of the stable proxy/plugin release workflow. See
[docs/connect-share-testing.md](docs/connect-share-testing.md) for the manual
singleplayer, direct-connect, and fallback acceptance pass.

## Integrating with login / auth plugins

Connect authenticates players at the edge, so login plugins that force online mode on a
Connect-tunneled connection hang the player's login. Connect publishes a stable
`connect-player` channel attribute (mirroring Floodgate's `floodgate-player`) so any login
plugin can detect a Connect player and skip its own flow.

See [docs/login-plugin-integration.md](docs/login-plugin-integration.md) for the supported
integration contract and its permanent-stability commitment.

## Bedrock identity verification

See [docs/bedrock-identity.md](docs/bedrock-identity.md) for legacy v1 behavior, signed-principal
v2 configuration, the trust boundary, and static-key pinning.

## Connect libp2p endpoint mode

The Connect libp2p endpoint path is enabled by configuring the Connect edge peer
address. It keeps the normal WatchService path available as fallback while the
endpoint also registers a stable libp2p peer for proxy-initiated session streams.

Environment variables:

- `CONNECT_LIBP2P_EDGE_ADDR`: comma-separated Connect edge libp2p multiaddrs,
  each including `/p2p/<connect-edge-peer-id>`. Setting this enables libp2p mode.
- `CONNECT_LIBP2P_LISTEN_ADDR`: optional endpoint listen multiaddrs. The
  default is `/ip4/127.0.0.1/tcp/0`.
- `CONNECT_LIBP2P_ADVERTISE_ADDRS`: optional explicit endpoint addresses
  to publish instead of the local listen addresses.
- `CONNECT_LIBP2P_RELAY_ADDRS`: optional relay bootstrap multiaddrs. The
  endpoint reserves each relay through these addresses. During registration,
  the Connect edge can challenge the endpoint to sign equivalent
  `/p2p-circuit/p2p/<endpoint-peer-id>` addresses that are better for other
  edge proxies to dial, such as private per-machine relay addresses.
- `CONNECT_WATCH_HEALTH_ADDR`: optional `host:port` address for an HTTP watcher
  health endpoint. `GET /healthz` returns `200` after the watcher opens and
  while the negotiated watchless endpoint remains ready; it returns `503`
  before opening, while reconnecting, and after stop, error, or completion.

## Working setups

When installing the Connect plugin the following platform settings are supported.

- PaperMC/Spigot
    - If running in Online mode you must set to `enforce-secure-profile: false` in [server.properties](https://minecraft.fandom.com/wiki/Server.properties)
    - For Paper/Spigot endpoints, set `settings.connection-throttle: -1` in `bukkit.yml`.
      Connect may retry the backend handshake while detecting the server's forwarding mode, and
      Paper's default connection throttle can reject that retry as `Connection throttled!`.
    - ✔️️ No forwarding + Online mode
    - ✔️ No forwarding + Offline mode
    - ✔️ Velocity forwarding + Online/Offline mode
    - ✔️ Bungee forwarding + Offline mode
    - ❌ Bungee forwarding + Online mode
- Velocity
    - ✔️ Velocity forwarding (aka modern) + Online/Offline mode
        - ❌ Can't connect to Velocity enabled PaperMC server through Velocity proxy
    - ✔️ Bungee forwarding (aka legacy) + Online/Offline mode
    - ✔️ None forwarding + Online/Offline mode
    - ✔️ `force-key-authentication: true` in [velocity.toml](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/resources/default-velocity.toml#L19)
- Bungee
    - ✔️ Bungee forwarding + Offline mode
    - ✔️ Bungee forwarding + Online mode
    - ✔️ `enforce_secure_profile: true` in [config.yml](https://www.spigotmc.org/wiki/bungeecord-configuration-guide/)

You can install the Connect plugin on any of the above platforms. The plugin will automatically
detect the platform and will configure itself accordingly.

You can even install Connect on Velocity or BungeeCord proxy and the Connect services treat it as a
normal Minecraft server. This allows you to use your existing proxy setup and still use the Connect
services. This ultimately allows you to add your Minecraft networks to the global Connect network.

You can also install the Connect plugin on your Spigot/PaperMc servers and still join them from your
own proxies as well as through the Connect network.

You don't need to use own proxies since the Connect network already works like a global shared proxy
where every Minecraft server/proxy can connect to.

## Special thanks

**Special thanks goes to the [GeyserMC](https://github.com/GeyserMC) developers for their Floodgate
and GeyserMC open source projects.** This repository forks Floodgate and only reuses its phenomenal
project layout for our plugin as well as the very similar internal player connection injection
methods applied. Note that our plugin is completely different from Floodgate and Geyser plugins as
it differs in functionality and should work alongside those as we have refactored our plugin to work
isolated from the upstream.
