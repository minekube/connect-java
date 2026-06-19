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
- `CONNECT_LIBP2P_RELAY_ADDRS`: optional relay multiaddrs. The endpoint
  reserves each relay and advertises `/p2p-circuit/p2p/<endpoint-peer-id>`
  addresses for the Connect edge to dial.

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

There will be public APIs available for you to manage your connected endpoints and players like
sending players a message, moving them between your servers and so on.

## Special thanks

**Special thanks goes to the [GeyserMC](https://github.com/GeyserMC) developers for their Floodgate
and GeyserMC open source projects.** This repository forks Floodgate and only reuses its phenomenal
project layout for our plugin as well as the very similar internal player connection injection
methods applied. Note that our plugin is completely different from Floodgate and Geyser plugins as
it differs in functionality and should work alongside those as we have refactored our plugin to work
isolated from the upstream.
