# Minekube Connect

Connect is a connector plugin that links a Minecraft server or proxy to the
global Connect Network through an outbound tunnel. Players can join at the
free `<endpoint>.play.minekube.net` address or a custom domain without opening
a port to the public Internet.

The Java plugin is the fast setup option for existing servers and proxies.
Install the jar for the platform where players first enter your network:

- **Paper or Spigot:** use `connect-spigot.jar`.
- **Velocity:** use `connect-velocity.jar`.
- **BungeeCord or Waterfall:** use `connect-bungee.jar`.

Connect-routed Java and Bedrock players use the same endpoint address. Bedrock
translation is handled by the Connect edge before traffic reaches this plugin;
the normal plugin setup does not require a backend Geyser installation.

For more routing features and the connector that receives the most frequent
updates, consider the [Gate connector](https://connect.minekube.com/guide/connectors/).

## Requirements

- **Minecraft 1.13+** on Paper/Spigot (and supported proxy versions on Velocity and
  BungeeCord) — the declared `api-version` floor and the first version whose servers
  ship the Netty 4.1+ the plugin's bundled Netty (4.2.x, unrelocated) requires.
  Servers 1.8–1.11 ship Netty 4.0.x, where the packet listener injection fails with
  `AbstractMethodError` (missing `newChild` implementation).
- **Java 17+** to run the plugin.

## Quick start

1. Download the jar for your platform and place it in the server or proxy
   `plugins` directory.
2. Start the server or proxy.
3. Optionally set `endpoint: your-server-name` in
   `plugins/connect/config.yml`.
4. Join using the public address printed in the console.

Minecraft 1.19 and newer secure-profile settings need platform-specific
configuration for Connect-routed players. Follow the plugin guide instead of
copying a setting between Paper, Velocity, and BungeeCord.

## Documentation and support

- [Plugin setup guide](https://connect.minekube.com/guide/connectors/plugin)
- [Compatibility matrix](https://connect.minekube.com/guide/compatibility)
- [Quick start](https://connect.minekube.com/guide/quick-start)
- [Source code](https://github.com/minekube/connect-java)
- [Community support](https://minekube.com/discord)
