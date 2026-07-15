# PR 57 Final Security Blockers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline execution selected by the captain-authorized task). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four final PR #57 blockers without widening legacy admission or exposing trusted identity data.

**Architecture:** Parse Bedrock identity configuration into a closed internal mode/policy value consumed by readiness and admission. Pin metadata to a parsed HTTPS URL, validate signed-envelope token shapes before Gson binding, and remove a displaced session's registry entry when its UUID is replaced.

**Tech Stack:** Java 17, Gson streaming `JsonReader`, OkHttp, JUnit 5, Gradle.

## Global Constraints

- Preserve Moxy #464 v1 wire and JSON semantics; noncanonical scalar JSON is rejected before Gson.
- Metadata uses only the exact configured HTTPS URL with no userinfo/fragment, no redirects, 5-second timeout, 64 KiB cap, and existing stale/backoff limits.
- Enforcement is exactly `disabled`, `warn`, or `require`; malformed config is unready and cannot publish claims.
- Preserve Java and documented UNSPECIFIED compatibility; envelope, nonce, and scope remain private.
- Do not change public docs, release, deploy, tag, or add dependencies.

---

### Task 1: Pin metadata to a parsed HTTPS source

**Files:**
- Modify: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityKeyProvider.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityKeyProviderTest.java`

**Interfaces:** `keys()` parses `metadataUrl` before building an OkHttp request and returns no remote keys for invalid URL input.

- [ ] **Step 1: Write failing URL contract tests**

```java
@ParameterizedTest
@ValueSource(strings = {
    "http://metadata.example/keys",
    "https://user:pass@metadata.example/keys",
    "https://metadata.example/keys#fragment"
})
void rejectsMetadataUrlOutsidePinnedHttpsContract(String metadataUrl) {
    assertThat(provider(config(metadataUrl)).keys()).isEmpty();
}
```

- [ ] **Step 2: Verify red**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityKeyProviderTest.rejectsMetadataUrlOutsidePinnedHttpsContract' --no-daemon`

Expected: FAIL because HTTP metadata is currently fetched.

- [ ] **Step 3: Implement parsed URL validation**

```java
private HttpUrl metadataUrl(String configured) {
    HttpUrl url = HttpUrl.parse(configured);
    if (url == null || !"https".equals(url.scheme()) || !url.username().isEmpty()
            || !url.password().isEmpty() || url.fragment() != null) {
        throw new IllegalArgumentException("metadata URL must be HTTPS without userinfo or fragment");
    }
    return url;
}
```

Build the request with the returned `HttpUrl`; retain disabled redirects and test valid remote metadata through TLS MockWebServer.

- [ ] **Step 4: Verify green**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityKeyProviderTest' --no-daemon`

Expected: PASS.

### Task 2: Enforce Moxy-exact JSON token shapes

**Files:**
- Modify: `api/src/main/java/com/minekube/connect/api/player/bedrock/BedrockIdentityVerifier.java`
- Test: `core/src/test/java/com/minekube/connect/api/player/bedrock/BedrockIdentityVerifierTest.java`

**Interfaces:** `decode(String)` accepts only nested objects, strings, and canonical integer JSON number tokens according to the v1 field schema.

- [ ] **Step 1: Write failing signed-envelope token tests**

```java
@ParameterizedTest
@ValueSource(strings = {
    "\"version\":\"1\"", "\"version\":1.0", "\"version\":1e0",
    "\"issued_at_unix_ms\":null", "\"expires_at_unix_ms\":true"
})
void rejectsSignedEnvelopeWithNonMoxyScalarToken(String replacement) {
    String envelope = sign(canonicalEnvelopeReplacedWith(replacement));
    assertThatThrownBy(() -> verifier().verify(profile(envelope)))
            .isInstanceOf(BedrockIdentityVerificationException.class);
}
```

- [ ] **Step 2: Verify red**

Run: `./gradlew :core:test --tests 'com.minekube.connect.api.player.bedrock.BedrockIdentityVerifierTest.rejectsSignedEnvelopeWithNonMoxyScalarToken' --no-daemon`

Expected: FAIL because Gson coerces quoted numeric values.

- [ ] **Step 3: Validate each scalar during the structural pass**

```java
private static void validateScalar(JsonReader reader, String object, String field)
        throws IOException, BedrockIdentityVerificationException {
    JsonToken token = reader.peek();
    if (integerField(object, field)) {
        if (token != JsonToken.NUMBER || !CANONICAL_INTEGER.matcher(reader.nextString()).matches()) throw invalidToken();
    } else if (stringField(object, field)) {
        if (token != JsonToken.STRING) throw invalidToken();
        reader.nextString();
    } else throw invalidToken();
}
```

Call it for every non-nested allowed field, including metadata cache integers where their JSON decoder applies; reject string/null/array/boolean/float/exponent substitutions.

- [ ] **Step 4: Verify green**

Run: `./gradlew :core:test --tests 'com.minekube.connect.api.player.bedrock.BedrockIdentityVerifierTest' --tests 'com.minekube.connect.api.player.bedrock.BedrockIdentityFixtureManifestTest' --no-daemon`

Expected: PASS with the 23 exact Moxy fixtures unchanged.

### Task 3: Share a closed configuration model between readiness and enforcement

**Files:**
- Create: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityConfiguration.java`
- Modify: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityReadiness.java`
- Modify: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityEnforcer.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityReadinessTest.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityEnforcerTest.java`
- Test: `core/src/test/java/com/minekube/connect/register/WatcherRegisterTest.java`
- Test: `core/src/test/java/com/minekube/connect/tunnel/p2p/Libp2pRuntimeTest.java`

**Interfaces:** `BedrockIdentityConfiguration.from(BedrockIdentityConfig)` returns a closed `Mode { DISABLED, WARN, REQUIRE }`, validated issuer/policy, and `isUsable()`.

- [ ] **Step 1: Write failing malformed-config tests**

```java
@ParameterizedTest
@MethodSource("malformedIdentityConfigs")
void malformedConfigurationNeverAdvertisesOrVerifies(ConnectConfig config) {
    assertThat(new BedrockIdentityReadiness(config, usableProvider(config)).isReady()).isFalse();
    assertThat(enforcer(config).verify(forgedBedrockPlayer(), endpointId, orgId)).isRejected();
}
```

Cover unknown/blank enforcement, blank issuer, unknown/blank policy, plus Watch/libp2p marker withdrawal after each live transition.

- [ ] **Step 2: Verify red**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityReadinessTest.malformedConfigurationNeverAdvertisesOrVerifies' --no-daemon`

Expected: FAIL because unknown enforcement currently advertises and allows warnings.

- [ ] **Step 3: Implement closed configuration**

```java
enum Mode { DISABLED, WARN, REQUIRE }

static BedrockIdentityConfiguration from(BedrockIdentityConfig source) {
    Mode mode = parseMode(source.getEnforcement());
    String issuer = nonEmpty(source.getExpectedIssuer());
    String policy = allowedPolicy(source.getExpectedPolicy()) ? source.getExpectedPolicy() : null;
    return new BedrockIdentityConfiguration(mode, issuer, policy);
}
```

Only WARN/REQUIRE with issuer and Moxy policy are usable. Readiness additionally requires validated keys. Enforcer rejects malformed identity-bearing Bedrock and UNSPECIFIED-envelope admissions and never records claims.

- [ ] **Step 4: Verify green**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityReadinessTest' --tests 'com.minekube.connect.bedrock.BedrockIdentityEnforcerTest' --tests 'com.minekube.connect.register.WatcherRegisterTest' --tests 'com.minekube.connect.tunnel.p2p.Libp2pRuntimeTest' --no-daemon`

Expected: PASS.

### Task 4: Remove displaced same-UUID claims deterministically

**Files:**
- Modify: `core/src/main/java/com/minekube/connect/api/SimpleConnectApi.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/SimpleConnectApiBedrockIdentityTest.java`

**Interfaces:** replacing a UUID clears the replaced player’s session registry entry; delayed A removal cannot erase B’s live claims, and B removal cannot retain A’s claims.

- [ ] **Step 1: Write failing A/B ordering test**

```java
@Test
void sameUuidReplacementRemovesDisplacedSessionClaimsInEitherDisconnectOrder() {
    api.addPlayer(sessionAWithClaims());
    api.addPlayer(sessionBWithSameUuid());
    api.playerRemoved(playerB.getUniqueId());
    assertThat(registry.get(playerA)).isEmpty();
    assertThat(registry.get(playerB)).isEmpty();
}
```

Repeat the delayed A callback while B is active and assert B’s claim remains.

- [ ] **Step 2: Verify red**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.SimpleConnectApiBedrockIdentityTest.sameUuidReplacementRemovesDisplacedSessionClaimsInEitherDisconnectOrder' --no-daemon`

Expected: FAIL because session A remains in the registry after B removal.

- [ ] **Step 3: Remove exact displaced/matching generations**

```java
ConnectPlayer previous = players.put(uuid, publicPlayer);
if (previous != null && previous != publicPlayer) {
    verifiedBedrockIdentities.remove(previous);
}
```

On removal, clear only the matching current or pending player object; never find a UUID and clear a newer session.

- [ ] **Step 4: Verify green**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.SimpleConnectApiBedrockIdentityTest' --tests 'com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistryTest' --tests 'com.minekube.connect.bedrock.ConnectPlatformBedrockIdentityLifecycleTest' --no-daemon`

Expected: PASS.

### Task 5: Refactor, commit, and run fresh pipeline validation

**Files:**
- Modify only files named in Tasks 1-4 and this plan.

- [ ] **Step 1: Run all report-focused suites**

Run: `./gradlew :core:test --tests 'com.minekube.connect.api.player.bedrock.BedrockIdentityFixtureManifestTest' --tests 'com.minekube.connect.api.player.bedrock.BedrockIdentityVerifierTest' --tests 'com.minekube.connect.bedrock.BedrockIdentityKeyProviderTest' --tests 'com.minekube.connect.bedrock.BedrockIdentityReadinessTest' --tests 'com.minekube.connect.bedrock.BedrockIdentityEnforcerTest' --tests 'com.minekube.connect.bedrock.SimpleConnectApiBedrockIdentityTest' --tests 'com.minekube.connect.bedrock.VerifiedBedrockIdentityRegistryTest' --tests 'com.minekube.connect.register.WatcherRegisterTest' --tests 'com.minekube.connect.tunnel.p2p.Libp2pRuntimeTest' :spigot:test :velocity:test :bungee:test --no-daemon`

Expected: PASS.

- [ ] **Step 2: Run full repository validation**

Run: `./gradlew build --no-daemon && git diff --check`

Expected: BUILD SUCCESSFUL and no whitespace errors.

- [ ] **Step 3: Commit**

Run: `git add api/src/main core/src/main core/src/test docs/superpowers/plans/2026-07-15-pr57-final-security-blockers.md && git commit -m "fix: harden Bedrock identity configuration"`

Expected: one compatible `fix:` commit on `fm/connect-java-bedrock-claims-u6`.

- [ ] **Step 4: Start fresh no-mistakes validation with original intent**

Run: `no-mistakes axi run --intent "Implement the captain-authorized Connect Java safe unlinked Bedrock support against Moxy PR #464 at merge commit 35ead853dbc0fcd1c577f6fcb2476217b35cc45b, preserving exact trusted session protocol markers, fixtures, verification, session-bound Optional claims, and all Java/plugin compatibility; harden metadata, malformed config, exact JSON decoding, and session replacement cleanup without deployment or release."`

Expected: PR #57 reaches `checks-passed`; stop at the CI-ready outcome.
