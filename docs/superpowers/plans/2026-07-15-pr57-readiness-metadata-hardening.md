# PR 57 Readiness and Metadata Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent a Connect Java endpoint from advertising `bedrock-identity-v1` unless it can verify identities, and bound remote verifier-key refresh against network and cache-expiry attacks.

**Architecture:** Introduce one injected readiness owner around the existing verifier-key provider. Both Watch request construction and libp2p registration obtain the capability list from that owner, which performs the initial metadata validation and reports readiness changes to their respective re-registration hooks. The provider owns a dedicated, non-redirecting 5-second metadata client, capped body decoding, validated-cache stale deadline, synchronized refresh, and a deterministic failure-backoff clock seam.

**Tech Stack:** Java 17, Guice, OkHttp, Gson, JUnit 5, MockWebServer, existing Connect Watch/libp2p transports.

## Global Constraints

- Start from PR #57 head `790428b377b90de03192b992e40c2f44126efd8a`; retain the explicit trusted-XUID API contract.
- Advertise `bedrock-identity-v1` only when enforcement is not `disabled` and a validated static or remote key source is usable.
- Metadata verification uses a dedicated 5-second call timeout, follows no redirects, reads at most 64 KiB before JSON parsing, and only retains validated keys through the hard stale deadline.
- Do not expose raw envelope, nonce, scope sidecar, or XUID via `toString`, incidental serialization, platform profile forwarding, or logs; `getBedrockXuid()` remains intentional, sensitive opt-in API data.
- Preserve Java/linked Bedrock behavior, public API binary compatibility, existing fixture bytes, and normal release automation.

---

### Task 1: Add shared verifier readiness and transport capability tests

**Files:**
- Create: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityReadiness.java`
- Modify: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityKeyProvider.java`
- Modify: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityEnforcer.java`
- Modify: `core/src/main/java/com/minekube/connect/module/CommonModule.java`
- Modify: `core/src/main/java/com/minekube/connect/watch/WatchClient.java`
- Modify: `core/src/main/java/com/minekube/connect/tunnel/p2p/PeerRegistrationHandshake.java`
- Modify: `core/src/main/java/com/minekube/connect/tunnel/p2p/Libp2pEndpointRuntime.java`
- Test: `core/src/test/java/com/minekube/connect/watch/WatchClientTest.java`
- Test: `core/src/test/java/com/minekube/connect/tunnel/p2p/PeerRegistrationHandshakeTest.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityReadinessTest.java`

**Interfaces:**
- Consumes: `BedrockIdentityKeyProvider.keys()` and `ConnectConfig.BedrockIdentityConfig`.
- Produces: `BedrockIdentityReadiness.isReady()` and `capabilities(List<String>)`, used by both transports; a state-change callback reopens Watch and re-registers libp2p only after a capability transition.

- [ ] **Step 1: Write failing readiness and transport tests**

```java
@Test void disabledOrMalformedIdentityConfigurationDoesNotAdvertiseCapability() {
    assertFalse(readiness(disabledConfig()).isReady());
    assertFalse(watchRequest(disabledConfig()).header("Connect-Capabilities")
            .contains("bedrock-identity-v1"));
    assertFalse(handshake(disabledConfig()).init(List.of()).getCapabilitiesList()
            .contains("bedrock-identity-v1"));
}

@Test void validatedStaticOrMetadataKeysAdvertiseOnBothTransports() {
    assertTrue(readiness(validStaticKeyConfig()).isReady());
    assertTrue(readiness(validMetadataConfig()).isReady());
}
```

- [ ] **Step 2: Run tests to verify red**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityReadinessTest' --tests 'com.minekube.connect.watch.WatchClientTest' --tests 'com.minekube.connect.tunnel.p2p.PeerRegistrationHandshakeTest' --no-daemon`

Expected: FAIL because capability insertion is unconditional and no readiness type exists.

- [ ] **Step 3: Implement a singleton readiness owner and use it in both transports**

```java
public final class BedrockIdentityReadiness {
    public boolean isReady() { return nonDisabledMode() && keyProvider.hasUsableKeys(); }
    public List<String> capabilities(List<String> base) {
        List<String> out = new ArrayList<>(base);
        out.remove(BEDROCK_IDENTITY_V1);
        if (isReady()) out.add(BEDROCK_IDENTITY_V1);
        return out;
    }
}
```

Bind one `BedrockIdentityKeyProvider` and one `BedrockIdentityReadiness` in `CommonModule`; inject the same readiness into `BedrockIdentityEnforcer`, `WatchClient`, and `Libp2pEndpointRuntime`. Build Watch headers and each libp2p registration from the current `capabilities(...)` result. On readiness changes, close and reopen the Watch socket through `WatcherRegister`, and close/retry the active libp2p registration so the next signed record withdraws or re-adds the marker.

- [ ] **Step 4: Run tests to verify green**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityReadinessTest' --tests 'com.minekube.connect.watch.WatchClientTest' --tests 'com.minekube.connect.tunnel.p2p.PeerRegistrationHandshakeTest' --no-daemon`

Expected: PASS, including disabled, malformed static key, metadata startup outage, valid static, valid metadata, and both transport registration cases.

- [ ] **Step 5: Refactor and commit**

Run: `./gradlew :core:spotlessApply :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityReadinessTest' --no-daemon`

Commit: `git add core && git commit -m "fix: gate Bedrock capability on verifier readiness"`

### Task 2: Bound metadata refresh and stale-key behavior

**Files:**
- Modify: `core/src/main/java/com/minekube/connect/bedrock/BedrockIdentityKeyProvider.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/BedrockIdentityKeyProviderTest.java`

**Interfaces:**
- Consumes: metadata URL and `Supplier<Instant>` test clock.
- Produces: `keys()` with one in-flight metadata fetch, validated-key stale fallback through `staleUntil`, and `hasUsableKeys()` for readiness.

- [ ] **Step 1: Write failing deterministic attack/outage tests**

```java
@Test void rejectsRedirectAndBodiesOver64KiBWithoutCachingThem() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "/keys"));
    server.enqueue(new MockResponse().setBody("x".repeat(65_537)));
    assertTrue(provider.keys().isEmpty());
    assertTrue(provider.keys().isEmpty());
}
@Test void metadataCallTimesOutAfterFiveSeconds() {
    server.enqueue(new MockResponse().setBody(validMetadata()).setBodyDelay(6, SECONDS));
    assertTrue(provider.keys().isEmpty());
}
@Test void concurrentExpiredReadsShareOneRefresh() throws Exception {
    runConcurrent(8, provider::keys);
    assertEquals(1, server.getRequestCount());
}
@Test void failureBackoffAvoidsRepeatedRequestsAndExpiresValidatedStaleKeys() {
    assertKeys(provider.keys(), validKey);
    advanceClock(Duration.ofSeconds(2));
    assertKeys(provider.keys(), validKey);
    advanceClock(Duration.ofSeconds(11));
    assertTrue(provider.keys().isEmpty());
}
```

- [ ] **Step 2: Run tests to verify red**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityKeyProviderTest' --no-daemon`

Expected: FAIL because the provider follows redirects, consumes unbounded bodies, and refreshes per caller.

- [ ] **Step 3: Implement dedicated bounded refresh**

```java
private static final long METADATA_BODY_LIMIT_BYTES = 64 * 1024;
private static final Duration METADATA_CALL_TIMEOUT = Duration.ofSeconds(5);

synchronized List<byte[]> keys() {
    if (cacheIsFresh(now())) return cachedKeys.keys;
    if (now().isBefore(nextRefreshAt)) return usableStaleOrStatic(now());
    try { return cacheValidated(fetchCappedNoRedirectMetadata()); }
    catch (IOException | RuntimeException failure) { return recordFailureAndUseStale(now()); }
}
```

Create the metadata client from the supplied base client with `callTimeout(5, SECONDS)` and `followRedirects(false)`. Read `ResponseBody.byteStream()` through a bounded stream before Gson; reject status, empty body, malformed/scope-invalid metadata, key-size failures, redirect, and body overflow without caching. Set `nextRefreshAt` by a capped deterministic failure delay, never beyond `staleUntil`; return only previously validated keys until that deadline, then static valid keys or empty.

- [ ] **Step 4: Run tests to verify green**

Run: `./gradlew :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityKeyProviderTest' --no-daemon`

Expected: PASS for redirect, size cap, timeout, malformed metadata, rotation, stale deadline, single-flight, and backoff.

- [ ] **Step 5: Commit**

Run: `./gradlew :core:spotlessApply :core:test --tests 'com.minekube.connect.bedrock.BedrockIdentityKeyProviderTest' --no-daemon`

Commit: `git add core && git commit -m "fix: bound Bedrock verifier metadata refresh"`

### Task 3: Preserve sensitive XUID contract without incidental disclosure

**Files:**
- Modify: `api/src/main/java/com/minekube/connect/api/player/bedrock/BedrockIdentityClaims.java`
- Modify: `api/src/main/java/com/minekube/connect/api/ConnectApi.java`
- Test: `api/src/test/java/com/minekube/connect/api/player/bedrock/BedrockIdentityClaimsTest.java`
- Test: `core/src/test/java/com/minekube/connect/bedrock/SimpleConnectApiBedrockIdentityTest.java`
- Test: existing Watch/Spigot/Velocity profile-forwarding tests

**Interfaces:**
- Consumes: the existing public `getBedrockXuid()` opt-in accessor and deprecated nonce compatibility shim.
- Produces: redacted `toString()` and sensitive-data Javadoc without changing the XUID getter or claim/session semantics.

- [ ] **Step 1: Write failing API and serialization tests**

```java
@Test void xuidIsAvailableOnlyThroughTheExplicitSensitiveGetter() {
    BedrockIdentityClaims claims = claims("12345678901234567");
    assertEquals("12345678901234567", claims.getBedrockXuid());
    assertFalse(claims.toString().contains("12345678901234567"));
    assertFalse(serialize(claims).contains("signed-envelope"));
}
```

- [ ] **Step 2: Run tests to verify red**

Run: `./gradlew :api:test :core:test --tests 'com.minekube.connect.bedrock.SimpleConnectApiBedrockIdentityTest' --tests 'com.minekube.connect.watch.SessionProposalTest' --no-daemon`

Expected: FAIL because Lombok-generated `toString()` includes the XUID.

- [ ] **Step 3: Implement redacted diagnostics and sensitive API documentation**

Replace Lombok-generated `toString()` with a manual redacted representation that omits XUID, username if treated sensitive, raw profile properties, nonce, and sidecar. Mark `getBedrockXuid()` Javadoc as sensitive trusted identity material intended only for in-process plugin authorization; document `ConnectApi#getVerifiedBedrockIdentity` similarly. Keep constructors/getters and nonce compatibility methods unchanged.

- [ ] **Step 4: Run tests to verify green**

Run: `./gradlew :api:test :core:test --tests 'com.minekube.connect.bedrock.SimpleConnectApiBedrockIdentityTest' --tests 'com.minekube.connect.watch.SessionProposalTest' :spigot:test :velocity:test :bungee:test --no-daemon`

Expected: PASS and proves raw envelope/nonce/sidecar are absent from public profiles, forwarding, serialization, and diagnostics while explicit XUID access remains available.

- [ ] **Step 5: Commit and full verification**

Run: `./gradlew :velocity:test :spigot:test :bungee:test build --no-daemon && git diff --check`

Commit: `git add api core spigot velocity bungee && git commit -m "fix: redact Bedrock identity diagnostics"`

### Task 4: Fresh delivery validation

**Files:**
- Modify: repository files produced by Tasks 1–3 only.

- [ ] **Step 1: Verify committed clean branch**

Run: `git status --short --branch && git log --oneline origin/main..HEAD`

Expected: only the intended PR #57 and hardening commits are present.

- [ ] **Step 2: Run fresh no-mistakes validation**

Run: `no-mistakes axi run --intent "Implement the captain-authorized Connect Java safe unlinked Bedrock support against Moxy PR #464..."`

Expected: drive only pipeline gates; do not hand-edit while the run is active, do not use `--yes`, and stop at `checks-passed`.
