# Velocity module agent instructions

The root `AGENTS.md` still applies; this file adds Velocity-specific guidance.

## Defensive login re-assert (proxy)

- Connect registers a second, late pre-login re-assert handler (see `VelocityLateEventRegistrar`).
  Preserve the property
  that makes it safe: it reacts **only** to Connect's own result on the event object, never
  reads/links against/version-checks a third-party plugin, never overrides a deny, and no-ops
  when nothing changed the decision. The existing `EARLY`/`LOWEST` handlers stay as they are -
  this only adds a floor. If late-handler registration throws, `VelocityListenerRegistration`
  catches `Throwable` locally, logs the failure, and continues with the pre-existing behavior;
  ordinary listener registration remains unchanged. Authoritative:
  `velocity/.../listener/VelocityLateEventRegistrar.java` (the two layered ordering levers and
  why each exists), `VelocityLateReassertListener`, `BungeeLateReassertListener`,
  `core/src/main/resources/proxy-config.yml` (`login-reassert`), `docs/login-plugin-integration.md`.
- **Do not bump velocity-api past `3.2.0-SNAPSHOT`** to reach `PostOrder.CUSTOM`: Velocity reads
  the annotation while collecting a listener's methods, so an enum constant an older runtime
  lacks throws `EnumConstantNotPresentException` there and kills *all* of Connect's handlers on
  pre-2024-09-16 proxies, unguardably. The reflective short-`register` lookup buys the same
  ordering with a catchable failure.
- Default profile scope is properties-only (skin) deliberately; restoring Connect's UUID breaks
  login plugins that key their storage on the proxy UUID, so it is opt-in with its
  `new-uuid-creator: MOJANG` prerequisite documented next to the option.
- `:velocity:eventOrderTest` is a separate source set running the **real** `VelocityEventManager`
  and `PluginDependencyUtils` against a Velocity proxy jar pinned by sha256 (ivy repo in
  `settings.gradle.kts`; PaperMC publishes no proxy artifact to Maven). Separate because that
  shaded jar carries its own velocity-api and `com.velocitypowered.proxy` classes, which must not
  shadow the 3.2.0 API or the stubs in `velocity/src/test`. It runs under `check`, so a
  fill-data.papermc.io outage fails `./gradlew build` until the artifact resolves or is cached.
