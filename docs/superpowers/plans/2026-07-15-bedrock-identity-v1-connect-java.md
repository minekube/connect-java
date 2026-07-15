# Bedrock Identity V1 Connect Java Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume Moxy PR #464's additive trusted Bedrock session contract without changing Java-player behavior, and publish only session-bound verified Bedrock claims to Connect plugins.

**Architecture:** Add Moxy's generated `SessionProtocol` enum to the local Watch and libp2p schema mirrors, carry it through the existing `SessionProposal`/`LocalSession.Context` boundary, and base admission solely on that marker. Harden the existing v1 verifier against the immutable Moxy fixture corpus, then publish successful verification through an internal session-keyed registry exposed by a default `ConnectApi` method.

**Tech Stack:** Java 17, protobuf, Gradle/JUnit 5, Gson, OkHttp, Guice, Velocity, Spigot/Paper, Bungee.

## Global Constraints

- Contract source is Moxy merge `35ead853dbc0fcd1c577f6fcb2476217b35cc45b`; Watch `Session.protocol` is field 6 and libp2p `SessionOffer.protocol` is field 8.
- `UNSPECIFIED=0`, `JAVA=1`, and `BEDROCK=2`; unknown values remain untrusted and must never become Java by inference.
- `bedrock-identity-v1` is advertised on both Watch and libp2p only after the marker, verifier, registry, and enforcement implementation are present.
- Preserve legacy absent-marker behavior, but never treat it as newly trusted unlinked Bedrock; Moxy gates that route by capability.
- Keep `bedrock-identity.enforcement=disabled` by default. Do not synthesize Floodgate/Mojang identity, broaden offline mode, or promise Bungee backend forwarding.
- Verify the 23 Moxy-owned fixtures byte-for-byte against the published SHA-256 manifest; never generate replacement Java fixtures.
- Do not log public keys, raw envelopes, XUIDs, profile dumps, nonces, signatures, or secrets.

---

## File Structure

- Modify `core/src/main/proto/com/minekube/connect/v1alpha1/watch_service.proto` and `core/src/main/proto/com/minekube/connect/v1alpha1/connect_libp2p.proto` to mirror the exact Moxy enum and additive fields.
- Modify `core/src/main/java/com/minekube/connect/watch/SessionProposal.java`, `core/src/main/java/com/minekube/connect/network/netty/LocalSession.java`, and `core/src/main/java/com/minekube/connect/tunnel/p2p/Libp2pSessionMapper.java` to preserve the marker through both transports.
- Modify `api/.../BedrockIdentityVerifier.java` and `BedrockIdentityClaims.java` to enforce signed v1 principal/profile/time/policy semantics.
- Modify `core/.../BedrockIdentityEnforcer.java`, `BedrockIdentityKeyProvider.java`, and config to enforce marker-aware behavior, issuer/key/rotation/staleness limits, and safe diagnostics.
- Create `core/.../BedrockIdentityRegistry.java` and expose it with a backward-compatible default in `api/.../ConnectApi.java`; integrate at platform admission/disconnect seams.
- Add immutable fixture resources and focused transport, verifier, enforcer, key-provider, registry, and Velocity/Spigot/Bungee lifecycle tests.

### Task 1: Mirror and transport the trusted session marker

**Files:**
- Modify: `core/src/main/proto/com/minekube/connect/v1alpha1/watch_service.proto`
- Modify: `core/src/main/proto/com/minekube/connect/v1alpha1/connect_libp2p.proto`
- Modify: `core/src/main/java/com/minekube/connect/watch/SessionProposal.java`
- Modify: `core/src/main/java/com/minekube/connect/network/netty/LocalSession.java`
- Modify: `core/src/main/java/com/minekube/connect/tunnel/p2p/Libp2pSessionMapper.java`
- Test: `core/src/test/java/com/minekube/connect/watch/SessionProposalTest.java`
- Test: `core/src/test/java/com/minekube/connect/tunnel/p2p/Libp2pSessionMapperTest.java`

**Interfaces:**
- Produces `SessionProtocol` context value from Watch field 6 and libp2p field 8.
- Consumes `SessionProtocol` only from authenticated proposal protobufs, never player profile data.

- [ ] **Step 1: Write failing Watch and libp2p mapping tests** for `JAVA`, `BEDROCK`, absent field (`UNSPECIFIED`), and an unknown enum numeric value that remains non-Java.
- [ ] **Step 2: Run the focused tests** with `./gradlew :core:test --tests '*SessionProposalTest' --tests '*Libp2pSessionMapperTest'`; expect compilation failure because the enum/fields/context accessors do not exist.
- [ ] **Step 3: Add the exact Moxy proto enum and fields**: enum values 0/1/2 in `watch_service.proto`, `Session.protocol = 6`, and `SessionOffer.protocol = 8`; regenerate through Gradle.
- [ ] **Step 4: Thread the generated enum through `SessionProposal`, `LocalSession.Context`, and `Libp2pSessionMapper`** without coercing unknown values.
- [ ] **Step 5: Re-run focused tests**; expect pass.

### Task 2: Import Go-owned contract fixtures and harden v1 verification

**Files:**
- Create: `core/src/test/resources/bedrock-identity-v1/manifest.json`
- Create: `core/src/test/resources/bedrock-identity-v1/*.json` (the exact 23 Moxy files)
- Modify: `api/src/main/java/com/minekube/connect/api/player/bedrock/BedrockIdentityVerifier.java`
- Modify: `api/src/main/java/com/minekube/connect/api/player/bedrock/BedrockIdentityClaims.java`
- Test: `core/src/test/java/com/minekube/connect/api/player/bedrock/BedrockIdentityVerifierTest.java`

**Interfaces:**
- Consumes Moxy fixture manifest public key and verification time.
- Produces immutable `BedrockIdentityClaims` only after signature, duplicate-property, issuer, scope, policy, principal, profile, clock, and replay checks.

- [ ] **Step 1: Write fixture-manifest tests** that load all named resources as bytes, hash with SHA-256, compare the manifest, and run each vector at `verification_time_unix_ms`.
- [ ] **Step 2: Run verifier tests**; expect failures for absent resources and current permissive v1 validation.
- [ ] **Step 3: Copy the exact Moxy testdata bytes and manifest**, preserving one-line JSON bytes and digest values exactly.
- [ ] **Step 4: Implement strict verifier checks**: exactly one reserved property; Ed25519 signature; version/issuer; endpoint/org/session/protocol/policy; positive canonical XUID; nonblank username; policy-principal relation; issued-at future skew and maximum lifetime; replay; XUID derived UUID/profile binding; linked UUID/name/profile binding; reject unknown/duplicate envelope fields.
- [ ] **Step 5: Re-run verifier tests**; expect all valid linked/unlinked and invalid corpus vectors to match their expected outcome.

### Task 3: Make enforcement marker-aware and bound key metadata trust

**Files:**
- Modify: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityEnforcer.java`
- Modify: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityKeyProvider.java`
- Modify: `core/src/main/java/com/minekube/connect/config/ConnectConfig.java`
- Modify: `core/src/main/resources/config.yml`
- Modify: `core/src/main/resources/proxy-config.yml`
- Test: `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityEnforcerTest.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityKeyProviderTest.java`

**Interfaces:**
- `verify(Context)` returns a decision for the authenticated protocol marker.
- Metadata keys are usable only with valid issuer, `Ed25519`, valid key encoding/size, bounded cache age, current plus previous rotation, and hard stale deadline.

- [ ] **Step 1: Write failing matrix tests**: marked Java absent envelope allows unchanged; marked Bedrock missing/forged/replayed/scope-mismatched rejects in `require`; `warn` allows without claims; unspecified absent follows legacy; Java carrying reserved property rejects in `require`; unlinked `bedrock_xuid` requires `trusted_bedrock_xuid`.
- [ ] **Step 2: Write failing metadata tests** for bad issuer/algorithm/key lengths, current/previous rotation, refresh success replacing old keys, stale-if-error within the configured bound, and `require` failure after hard stale expiration.
- [ ] **Step 3: Run focused enforcer/key tests**; expect failures because the current enforcer hard-codes Bedrock and the provider permits indefinite stale cache.
- [ ] **Step 4: Implement marker-aware decisions and bounded metadata state** with defaults `disabled`, issuer `minekube-connect`, maximum envelope lifetime/future skew from contract, and finite `metadata-max-stale-seconds`; keep log reasons bounded and secret-free.
- [ ] **Step 5: Re-run focused tests**; expect pass.

### Task 4: Publish immutable session-bound verified claims

**Files:**
- Modify: `api/src/main/java/com/minekube/connect/api/ConnectApi.java`
- Modify: `core/src/main/java/com/minekube/connect/api/SimpleConnectApi.java`
- Create: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityRegistry.java`
- Modify: `core/src/main/java/com/minekube/connect/network/netty/LocalSession.java`
- Modify: `velocity/src/main/java/com/minekube/connect/listener/VelocityListener.java`
- Modify: `spigot/src/main/java/com/minekube/connect/addon/data/SpigotDataHandler.java`
- Modify: `bungee/src/main/java/com/minekube/connect/listener/BungeeListener.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityRegistryTest.java`
- Test: platform listener/data-handler tests in `velocity`, `spigot`, and `bungee`.

**Interfaces:**
- `default Optional<BedrockIdentityClaims> ConnectApi#getVerifiedBedrockIdentity(ConnectPlayer)` returns empty unless a currently connected session was verified.
- Registry has package-private mutation only, keyed by session ID, and lifecycle cleanup removes claims on disconnect/rejection.

- [ ] **Step 1: Write failing registry/API tests** for immutable claim exposure, session key isolation, duplicate/replay sessions, Java/disabled/warn-failed/rejected lookups empty, and disconnect cleanup.
- [ ] **Step 2: Run focused registry tests**; expect failure because the default API and registry are absent.
- [ ] **Step 3: Add registry and default API method** with no public setter or raw envelope representation; store claims only after a successful `Decision` for marked Bedrock.
- [ ] **Step 4: Wire Velocity, Spigot/Paper, and Bungee admission before profile rewriting, and wire all existing disconnect paths to cleanup.** Retain each platform’s existing UUID/name/profile behavior.
- [ ] **Step 5: Run platform-focused tests** for Java control, linked Bedrock, valid unlinked Bedrock, rejection, duplicate session, and disconnect cleanup; expect pass.

### Task 5: Advertise capability and prevent mixed-fleet unsafe routing

**Files:**
- Modify: `core/src/main/java/com/minekube/connect/watch/WatchClient.java`
- Modify: `core/src/main/java/com/minekube/connect/tunnel/p2p/PeerRegistrationClient.java`
- Test: `core/src/test/java/com/minekube/connect/watch/WatchBootstrapTest.java`
- Test: `core/src/test/java/com/minekube/connect/tunnel/p2p/PeerRegistrationClientTest.java`

**Interfaces:**
- Watch sends `Connect-Capabilities: bedrock-identity-v1`.
- libp2p registration includes exactly one `bedrock-identity-v1` capability.

- [ ] **Step 1: Write failing Watch header and libp2p peer-registration capability tests**, including absence of duplicate capability values.
- [ ] **Step 2: Run focused capability tests**; expect current requests/registration to omit the capability.
- [ ] **Step 3: Add the capability to both real transport registration seams**, keeping all other capability behavior untouched.
- [ ] **Step 4: Re-run focused capability and transport compatibility tests**; expect pass and old payload marker fallback retained.

### Task 6: Full regression, formatting, durable project note, and commit

**Files:**
- Modify only if durable guidance is absent: `AGENTS.md`

- [ ] **Step 1: Run `fm-ensure-agents-md.sh .`** and retain only durable pointers to protocol source and verification commands if it changes the project guide.
- [ ] **Step 2: Run focused suites** for verifier, enforcer, key provider, Watch, libp2p, registry, and every platform module.
- [ ] **Step 3: Run `./gradlew :velocity:test`, then `./gradlew build`**; expect `BUILD SUCCESSFUL`.
- [ ] **Step 4: Run configured formatter/lint tasks and `git diff --check`; inspect `git status --short` and changed files for fixture byte integrity and scope.**
- [ ] **Step 5: Commit the complete compatible release lane** using a Conventional Commit `feat:` message; do not tag, release, deploy, or alter another repository.

## Self-Review

- Contract coverage: Tasks 1 and 5 cover both Moxy transports, exact field numbers, capability, and legacy fallback; Tasks 2-3 cover every requested cryptographic, scope, profile, policy, replay, rotation, and stale-cache invariant; Task 4 covers the compatible API and every platform lifecycle seam; Task 6 covers required broad verification and commit.
- Placeholder scan: no `TODO`, `TBD`, or deferred implementation language remains; all behavior has a named production seam and focused test command.
- Type consistency: the marker is protobuf `SessionProtocol`; only `BEDROCK` may yield `BedrockIdentityClaims`; `ConnectApi` exposes `Optional<BedrockIdentityClaims>` and registry mutation is internal/session-keyed.
- Scope check: Moxy routing and owner controls remain outside this repository; this plan intentionally only advertises the capability and verifies the contract.
