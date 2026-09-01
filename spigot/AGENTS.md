# Spigot module agent instructions

The root `AGENTS.md` still applies; this file adds Spigot/Paper-specific guidance.

## Third-party platform APIs (ViaVersion & friends)

- Connect runs against a wide range of server/plugin versions, so an API that
  drifts across majors must not be bound at compile time. Resolve cross-version
  accessors reflectively by name (newest first) and degrade to skipping the
  workaround with a warning when no known accessor exists; see
  `SpigotInjector#unwrapViaInitializer` and `SpigotInjectorViaLegacyPathTest`.
- Only plain Spigot/CraftBukkit takes Via's wrapping (legacy) injector path. On
  Paper `BukkitViaInjector` registers a `ChannelInitializeListener` instead, which
  Connect's local channel picks up for free - so Paper never exercises the unwrap.
- Injector failures must stay diagnosable: `ConnectPlatform.enable()` catches
  `Throwable` (not `Exception`) because reflective signature drift arrives as an
  `Error`, making it a logged, orderly injection failure rather than an unhandled
  `Error` escaping `onEnable()`. `SpigotPlatform.enable()` still disables the plugin
  on a false return, so the value is the diagnosable log line, not continued operation.
  Guarded by `core/.../ConnectPlatformEnableFailureContainmentTest`.
- Compile-only platform deps live in `build-logic/.../Versions.kt` and are excluded
  from the shaded jar by `provided(...)`; the `viaversion-bukkit` artifact declares
  no transitive deps, so `viaversion-common` must be requested explicitly.
- Spigot NMS drift is expected when a Minecraft release renames an internal accessor:
  `spigot/.../util/ClassNames.java` resolves server internals in one static initializer,
  and failures are latched in the separate `NmsDiagnostics` class so they remain
  available after `ClassNames` becomes erroneous. `SpigotPlatform.enable()` logs the
  accessor and environment from that latch. Route new lookups through the
  `NmsDiagnostics` helpers so they stay reportable; `spigot/.../util/NmsDiagnosticsTest`
  guards this contract.
