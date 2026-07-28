# Login / auth plugin integration

**Audience:** authors of login, auth, or premium-detection plugins (LibreLogin, AuthMe,
nLogin, and anything similar) that run on a proxy or server which also runs Minekube
Connect.

**Summary:** Connect authenticates players at its edge and hands your proxy an
already-verified, offline-mode connection. Connect publishes a stable marker on that
connection — the `connect-player` Netty channel attribute — so your plugin can detect a
Connect-tunneled player and skip its own login flow. This is exactly the pattern many
plugins already implement for Floodgate's `floodgate-player` attribute.

## Why the marker exists

Connect terminates the real client connection at the Minekube edge, performs the Mojang
online-mode handshake there, and then relays the player's traffic into a local channel
inside your proxy. On that inner connection, Connect marks the player **offline mode**
and supplies the real Mojang UUID, username, and skin properties itself.

There is no second Mojang session for the proxy to verify. So any plugin that later forces
**online mode** on a Connect-tunneled connection makes the proxy send an
`EncryptionRequest` that can never be answered — the player's login hangs and never
completes. The same applies to rewriting the game profile after Connect has set it: the
player ends up with the wrong UUID and no skin.

The `connect-player` attribute lets you detect that situation before you act.

## The contract

### `connect-player` — channel attribute

| | |
|---|---|
| Attribute name | `connect-player` (exact, permanent) |
| Netty key | `io.netty.util.AttributeKey.valueOf("connect-player")` |
| Constant | `com.minekube.connect.api.ConnectAttributes.CONNECT_PLAYER` |
| Value type | `com.minekube.connect.api.player.ConnectPlayer` (never `null` when the attribute is set) |
| Set on | The proxy-facing channel, at channel creation, before any platform login event fires |
| Platforms | Velocity, BungeeCord, Spigot — one set-site covers all three |
| Set when | The session is authenticated by Connect, i.e. `Auth#isPassthrough()` is `false` |
| Absent when | The connection is not Connect-tunneled, **or** it is a passthrough session |

Passthrough sessions are deliberately left unmarked: Connect did not authenticate them, so
they should still go through your plugin's normal login flow.

Because the marker is set when the channel is created, it is already present in Velocity's
`PreLoginEvent` and `GameProfileRequestEvent`, in BungeeCord's `PreLoginEvent`, and in
Spigot's login handling — including handlers registered at the very earliest priority.

### `ConnectApi` — UUID lookups for online players

Once a player has finished logging in, `com.minekube.connect.api.ConnectApi` answers the
same question by UUID:

```java
ConnectApi api = ConnectApi.getInstance();
boolean tunneled = api.isConnectPlayer(uuid);   // is this online player tunneled by Connect?
ConnectPlayer player = api.getPlayer(uuid);     // null if not
```

These are the right tool for post-login checks (session tracking, limbo routing, command
gating). They are **not** usable during login, because the player is not registered yet —
that is what the channel attribute is for.

## How to integrate

If your plugin already exempts Floodgate players, the Connect exemption is the identical
shape. Add it at every point where you would otherwise override the connection's
authentication decision or identity.

**Presence check, with no dependency on Connect** — Netty interns attribute keys by name,
so this compiles and works whether or not Connect is installed:

```java
private static final AttributeKey<Object> CONNECT_PLAYER =
        AttributeKey.valueOf("connect-player");

private static boolean isConnectPlayer(Channel channel) {
    return channel.hasAttr(CONNECT_PLAYER) && channel.attr(CONNECT_PLAYER).get() != null;
}
```

**Velocity — pre-login.** Reach the channel the same way you already do for Floodgate
(`LoginInboundConnection#delegate` → `InitialInboundConnection` → `MinecraftConnection` →
`Channel`), then bail out:

```java
@Subscribe(order = PostOrder.LAST)
public void onPreLogin(PreLoginEvent event) {
    Channel channel = channelOf(event.getConnection());
    if (channel != null && isConnectPlayer(channel)) {
        return; // externally authenticated by Connect - never force online mode
    }
    ...
}
```

**Velocity — game profile.** Do not rebuild the profile from the original one; Connect has
already put the player's real UUID and skin properties in it.

**BungeeCord — pre-login.** Same check before any `setOnlineMode(true)`.

**Post-login / session tracking.** Use `ConnectApi.isConnectPlayer(player.getUniqueId())`
to skip your own authorization tracking, limbo routing, and command blocking, the same way
you skip them for Floodgate players.

**Optional: read the player.** If you add Connect's `api` artifact as a dependency you can
use `ConnectAttributes.CONNECT_PLAYER` directly and read the `ConnectPlayer` value for the
player's UUID, username, game profile, and `Auth`. That is why the attribute value is a
`ConnectPlayer` rather than a bare UUID — presence alone costs you nothing, and the full
identity is there when you want it.

## Connect defends its own decision (operators)

Not every login plugin exempts Connect, so Connect no longer relies on one. On proxies it
registers a second pre-login handler that runs **after every other plugin's** and restores its
own decision if something changed it. That is what makes a LibreLogin/AuthMe/nLogin-style
"force online mode for everyone" stop hanging Connect players at *Logging in…*, with no
upstream change and no need to disable premium autologin.

It is a **floor, not a veto**:

- It only ever acts on connections **Connect itself authenticated** (`Auth#isPassthrough()`
  is `false`). Everything else is untouched.
- It **never overrides a kick.** If any plugin denied the login, the deny stands — Connect
  never turns a kick into a join.
- It does **nothing** when the decision is still what Connect set, which is the normal case
  when no conflicting plugin is installed.
- It keys only off *Connect's own* result. Connect does not read, link against, or
  version-check any login plugin, so this covers every plugin with this behaviour, present
  and future.

### Config

Both settings live in Connect's `config.yml` on Velocity and BungeeCord:

```yaml
login-reassert:
  enabled: true
  restore-full-profile: false
```

**`enabled`** (default `true`) — turn it off if you *deliberately* want another plugin to be
able to change Connect's login decision. Off restores exactly the previous behaviour.

**`restore-full-profile`** (default `false`) — by default Connect only restores the profile
**properties** (the skin), leaving the UUID as the login plugin set it. Turning this on also
restores the player's **Mojang UUID and username**, which is what makes
`ConnectApi.isConnectPlayer(uuid)` and `getPlayer(uuid)` resolve for these players.

> **Prerequisite for `restore-full-profile: true`:** every login plugin on the proxy must key
> its own database on the Mojang UUID. For **LibreLogin** that means setting
> **`new-uuid-creator: MOJANG`** in LibreLogin's config *first*. Its default (`CRACKED`)
> stores players under an offline UUID derived from the name, so restoring the Mojang UUID
> makes LibreLogin's own lookups miss and its join handlers throw on every login. This is why
> the option is opt-in rather than the default.

On BungeeCord there is no profile-properties API at pre-login, so `enabled` re-asserts offline
mode only, and `restore-full-profile` is what additionally re-asserts the UUID and username.

### How the ordering works (contributors)

- **Velocity:** the handlers are registered through
  `EventManager#register(Object, Class, short, EventHandler)` at `Short.MIN_VALUE`. Velocity
  maps `PostOrder.LAST` to `Short.MIN_VALUE + 1`, so this is one slot below it and wins
  regardless of plugin load order. That overload only exists on Velocity builds from
  2024-09-16 onwards; it is feature-detected with a single reflective lookup on the public
  interface, and older builds fall back to `PostOrder.LAST` plus the `optional` `librelogin`
  dependency in `velocity-plugin.json` — Velocity breaks ties between equal orders by plugin
  load order, which is a topological sort of the declared dependency graph, and an optional
  dependency on an absent plugin is a silent no-op. Velocity's API version is deliberately
  **not** bumped: reading a `PostOrder` constant an older runtime lacks throws
  `EnumConstantNotPresentException` while Velocity collects the listener's methods, which
  would kill *all* of Connect's handlers on older proxies and cannot be guarded against.
- **BungeeCord:** `@EventHandler(priority = Byte.MAX_VALUE)`. `EventPriority` is a set of
  `byte` constants (`HIGHEST = 64`), not an enum, and the bus dispatches the whole byte range.
  Handlers of equal priority sit in an identity-keyed `HashMap`, so registration order — and
  therefore `softDepends` — decides nothing there; the numeric priority is the only lever.

Pinned by `velocity/src/eventOrderTest/.../VelocityLateEventOrderTest` (which runs the real
`VelocityEventManager` and `PluginDependencyUtils` against a pinned Velocity proxy jar),
`velocity/src/test/.../VelocityLateEventRegistrarTest`,
`velocity/src/test/.../VelocityLateReassertListenerTest` and
`bungee/src/test/.../BungeeLateReassertListenerTest`.

## Stability commitment

**`connect-player` is a permanent public contract.**

Minekube commits to this explicitly: once external plugins depend on the name
`connect-player`, it will **never be removed or renamed**. The attribute name, the fact
that it is set before any login event, and the rule that it is absent for passthrough
sessions are all part of Connect's published API surface — treat them exactly like a
published method signature. Changes may only be additive.

This is a deliberate commitment, not an accident of the implementation. A future
contributor must not treat `connect-player` as an internal detail that is free to be
refactored, renamed, or moved to a later point in the connection lifecycle. Doing so
silently breaks every third-party login plugin that integrates with Connect, with no
compile error anywhere to warn about it.

The commitment is enforced in code by
`core/src/test/java/com/minekube/connect/network/netty/ConnectPlayerAttributeBoundaryTest.java`,
which pins both the exact name and the set-site; a rename or a moved marker fails the
build.

## Checking a login plugin for compatibility

- **Conflicts with Connect** if it can force online mode at pre-login
  (`forceOnlineMode()` / `setOnlineMode(true)`), or if it rewrites the game profile after
  Connect has set it. Connect's own re-assert (above) keeps such a plugin from breaking
  logins, but exempting Connect explicitly is still the better fix — it keeps the plugin's own
  state consistent instead of having its decision quietly reverted.
- **Compatible by design** if it only ever acts on offline-mode connections and never
  forces online mode.
- **If it has a Floodgate exemption**, ask its author to extend that exemption to
  Connect's `connect-player` attribute — the code is the same three lines.

## Related source

- `api/src/main/java/com/minekube/connect/api/ConnectAttributes.java` — the attribute key
  and its Javadoc contract
- `api/src/main/java/com/minekube/connect/api/ConnectApi.java` — `isConnectPlayer(UUID)` /
  `getPlayer(UUID)`
- `api/src/main/java/com/minekube/connect/api/player/Auth.java` — `isPassthrough()`
- `core/src/main/java/com/minekube/connect/network/netty/LocalServerChannelWrapper.java` —
  the single set-site
- `velocity/src/main/java/com/minekube/connect/listener/VelocityLateReassertListener.java` and
  `bungee/src/main/java/com/minekube/connect/listener/BungeeLateReassertListener.java` — the
  defensive re-assert
- `velocity/src/main/java/com/minekube/connect/listener/VelocityLateEventRegistrar.java` — the
  two layered ordering levers
- `core/src/main/resources/proxy-config.yml` — the `login-reassert` options
