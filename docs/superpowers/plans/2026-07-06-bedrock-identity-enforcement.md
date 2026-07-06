# Bedrock Identity Enforcement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in Connect Java enforcement for Moxy-signed Bedrock identity envelopes.

**Architecture:** Add config for static-key enforcement, a focused core enforcer around `BedrockIdentityVerifier`, and platform hook calls at existing login boundaries. Default behavior remains disabled so current servers are unaffected until operators configure enforcement.

**Tech Stack:** Java 17, Gradle, JUnit 5, Gson/configutils, Connect Java core/proxy/spigot modules.

---

## File Structure

- Modify `core/src/main/java/com/minekube/connect/config/ConnectConfig.java` to expose `BedrockIdentityConfig`.
- Modify `core/src/main/resources/config.yml` and `core/src/main/resources/proxy-config.yml` to document defaults.
- Modify `api/src/main/java/com/minekube/connect/api/player/bedrock/BedrockIdentityVerifier.java` so endpoint ID and org ID checks are optional expected scope fields.
- Extend `core/src/test/java/com/minekube/connect/api/player/bedrock/BedrockIdentityVerifierTest.java` for partial-scope verification.
- Create `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityEnforcer.java` for enforcement decisions.
- Create `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityEnforcerTest.java` for core behavior.
- Modify `velocity/src/main/java/com/minekube/connect/listener/VelocityListener.java` to reject/warn in `PreLoginEvent`.
- Modify `bungee/src/main/java/com/minekube/connect/listener/BungeeListener.java` to reject/warn in `PreLoginEvent`.
- Modify `spigot/src/main/java/com/minekube/connect/addon/data/SpigotDataHandler.java` to reject/warn before login profile mutation.
- Extend existing tests where seams exist, especially `spigot/src/test/java/com/minekube/connect/addon/data/SpigotDataHandlerTest.java`.

## Task 1: Config Parsing

- [ ] Add a failing test in `ConfigLoaderTest` that loads:

```yaml
bedrock-identity:
  enforcement: require
  public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
  expected-policy: trusted_bedrock_xuid
```

and asserts `config.getBedrockIdentity().getEnforcement()` is `require`.

- [ ] Run `./gradlew :core:test --tests com.minekube.connect.config.ConfigLoaderTest --no-daemon` and verify the test fails because config accessors do not exist.
- [ ] Add `ConnectConfig.BedrockIdentityConfig` with fields `enforcement`, `publicKey`, and `expectedPolicy`; default enforcement is `disabled`, expected policy is `trusted_bedrock_xuid`.
- [ ] Add the YAML block to `config.yml` and `proxy-config.yml`.
- [ ] Re-run the focused config test and commit:

```bash
git add core/src/main/java/com/minekube/connect/config/ConnectConfig.java core/src/main/resources/config.yml core/src/main/resources/proxy-config.yml core/src/test/java/com/minekube/connect/config/ConfigLoaderTest.java
git commit -m "feat: configure Bedrock identity enforcement"
```

## Task 2: Core Enforcer

- [ ] Add a failing verifier test showing a signed envelope verifies when endpoint name, session ID, protocol, and policy match but endpoint ID/org ID are not supplied to the builder.
- [ ] Run `./gradlew :core:test --tests com.minekube.connect.api.player.bedrock.BedrockIdentityVerifierTest --no-daemon` and verify it fails because endpoint ID/org ID are currently required.
- [ ] Update `BedrockIdentityVerifier` so endpoint ID/org ID are optional expected scope fields. The envelope must still contain both fields; the verifier only skips equality checks for expected values that are not configured.
- [ ] Re-run the focused verifier test.
- [ ] Add failing tests in `BedrockIdentityEnforcerTest` for disabled no-op, require success, require missing property, warn missing property, invalid key fail-open in warn, invalid key fail-closed in require, and replay rejection.
- [ ] Run `./gradlew :core:test --tests com.minekube.connect.bedrock.BedrockIdentityEnforcerTest --no-daemon` and verify failures are for missing enforcer code.
- [ ] Implement `BedrockIdentityEnforcer` with:

```java
public Decision verify(LocalSession.Context context)
```

where `Decision` exposes `allowed()`, `message()`, and `verifiedClaims()`.

- [ ] The implementation must not log raw `minekube:bedrock_identity` values or public keys.
- [ ] Re-run the focused enforcer test and commit:

```bash
git add api/src/main/java/com/minekube/connect/api/player/bedrock/BedrockIdentityVerifier.java core/src/test/java/com/minekube/connect/api/player/bedrock/BedrockIdentityVerifierTest.java core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityEnforcer.java core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityEnforcerTest.java
git commit -m "feat: enforce Bedrock identity envelopes"
```

## Task 3: Platform Hooks

- [ ] Add the enforcer injection to Velocity, Bungee, and Spigot login paths.
- [ ] Velocity: call the enforcer inside `LocalSession.context(channel, ctx -> ...)` before `forceOfflineMode()` and cache insertion; reject through `event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text(message)))` on deny.
- [ ] Bungee: call the enforcer before setting online mode/UUID/name; reject through `event.setCancelReason(message)` and `event.setCancelled(true)` on deny.
- [ ] Spigot: call the enforcer before spoofed UUID/profile mutation; close the channel and reject the proposal on deny.
- [ ] Add or extend tests for the available seams, then run focused platform tests:

```bash
./gradlew :spigot:test --tests com.minekube.connect.addon.data.SpigotDataHandlerTest --no-daemon
```

- [ ] Commit:

```bash
git add velocity/src/main/java/com/minekube/connect/listener/VelocityListener.java bungee/src/main/java/com/minekube/connect/listener/BungeeListener.java spigot/src/main/java/com/minekube/connect/addon/data/SpigotDataHandler.java spigot/src/test/java/com/minekube/connect/addon/data/SpigotDataHandlerTest.java
git commit -m "feat: reject invalid Bedrock identity sessions"
```

## Task 4: Verification and Tracking

- [ ] Run:

```bash
./gradlew :core:test --tests com.minekube.connect.config.ConfigLoaderTest --no-daemon
./gradlew :core:test --tests com.minekube.connect.bedrock.BedrockIdentityEnforcerTest --no-daemon
./gradlew build --no-daemon
git diff --check
```

- [ ] Open a PR to `minekube/connect-java` linking `minekube/moxy#403` and `minekube/moxy#396`.
- [ ] Ensure CI passes, merge if clean, and verify release automation.
- [ ] Comment on moxy issues with PR, commit, checks, and remaining key-discovery/docs follow-ups.
