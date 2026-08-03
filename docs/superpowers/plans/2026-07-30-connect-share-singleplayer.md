# Connect Share Singleplayer Ingress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first working Connect Share vertical slice: a Kotlin Fabric client mod for Minecraft 1.21.11 and 26.2 that privately publishes the current singleplayer world, reuses or imports one persistent Connect endpoint identity, accepts paid and non-paid vanilla Java guests through Connect, and asks the host to approve each guest.

**Architecture:** Pure Kotlin domain logic lives in `share/common`; reusable Fabric lifecycle and presentation logic lives in `share/fabric-common`; the two Fabric modules contain only their exact Minecraft adapter, mixins, resources, and packaging rules. A small Java extension to Connect Core adds asynchronous session admission and reusable credential primitives. The integrated server binds vanilla only to loopback, while Connect tunnels enter through `LocalServerChannelWrapper` using the captured vanilla child initializer.

**Tech Stack:** Gradle 9.5.1, Fabric Loom 1.17.17, Fabric Loader 0.19.3, Fabric API 0.141.6+1.21.11 and 0.156.0+26.2, Fabric Language Kotlin 1.13.13+kotlin.2.4.10, Kotlin 2.4.10, Java 21 and 25 toolchains, JUnit 5, MockWebServer, Netty Local transport, Connect WatchService, jvm-libp2p 1.3.5-RELEASE.

## Global Constraints

- Work only in the Treehouse worktree on `codex/connect-share-mod`; preserve the root worktree and all user changes.
- Keep plugin artifacts, mod artifacts, and production rollout as separate gates.
- Support exactly Minecraft `1.21.11` on Java 21 and Minecraft `26.2` on Java 25 in this plan.
- Use `net.fabricmc.fabric-loom-remap` for 1.21.11 and `net.fabricmc.fabric-loom` for 26.2.
- Product logic is Kotlin. Java is permitted only for mixins/accessors and the existing Java Core extension.
- Never bind a Minecraft listener to a wildcard, LAN, or WAN address. The vanilla TCP listener must bind `InetAddress.getLoopbackAddress()`.
- Persist one endpoint name and token per installation. A world or share ID must never generate endpoint credentials.
- Accept `CONNECT_ENDPOINT` and `CONNECT_TOKEN` with per-field precedence over disk.
- Dashboard imports require endpoint name plus token, validate before persistence, and leave the previous identity intact on every failure.
- Do not automatically rotate an endpoint name or token after authentication failure.
- Connect is the only relay. This plan does not add a second relay service.
- Accept paid and non-paid Connect sessions. Managed non-passthrough profiles are Connect-authenticated; locally accepted offline profiles are unverified and connection-scoped.
- Host approval expires after 30 seconds. At most 16 approvals may be pending and at most 16 guests may be configured.
- Non-passthrough Connect profiles are approved before tunnel creation. Passthrough profiles are approved after Minecraft resolves local authentication but before world entry.
- Preserve the reflective libp2p boundary. Parent-facing signatures must not expose `io.libp2p`, isolated `io.netty`, or isolated `kotlin` types.
- Every implementation task is test-first and ends in a focused Conventional Commit.

## Delivery Split

This plan is the independently testable singleplayer-through-Connect slice. It ends with two installable Fabric JARs and real Connect ingress. The already approved direct-P2P scope follows in a second plan after this slice is green: signed invitations, automatic LAN libp2p, opt-in internet direct attempts, and Connect fallback.

## File Map

### Build and automation

- `gradle/wrapper/gradle-wrapper.properties` - Gradle 9.5.1 wrapper.
- `settings.gradle.kts` - Fabric repositories/plugins and four Share projects.
- `build.gradle.kts` - keeps Java-11 plugin conventions away from Fabric projects.
- `build-logic/src/main/kotlin/Versions.kt` - pins Loom/Fabric/Kotlin/Arrow/libp2p versions.
- `share/AGENTS.md` - requires appropriate Arrow abstractions throughout the Kotlin mod.
- `.github/workflows/pullrequest.yml` - plugin matrix plus isolated Java-21/25 mod jobs.

### Connect Core extension

- `core/src/main/java/com/minekube/connect/identity/EndpointTokenStore.java` - plugin-compatible token loading, generation, owner-only atomic persistence, and redaction.
- `core/src/main/java/com/minekube/connect/watch/SessionAdmissionGate.java` - asynchronous pre-tunnel admission port.
- `core/src/main/java/com/minekube/connect/watch/SessionAdmissionDecision.java` - allow/defer/deny result with safe guest message.
- `core/src/main/java/com/minekube/connect/watch/AllowAllSessionAdmissionGate.java` - preserves plugin behavior.
- `core/src/main/java/com/minekube/connect/register/WatcherRegister.java` - invokes the gate before `Tunneler.prepare` or `LocalSession.connect`.
- `core/src/main/java/com/minekube/connect/ConnectPlatform.java` - accepts a prebuilt `ConnectConfig` for embedded clients.
- `core/src/main/java/com/minekube/connect/config/ConnectConfig.java` - explicit embedded configuration factory.
- `core/src/main/java/com/minekube/connect/module/CommonModule.java` - uses `EndpointTokenStore`.

### Loader-neutral Kotlin domain

- `share/common/src/main/kotlin/com/minekube/connect/share/identity/EndpointIdentity.kt` - endpoint/token value and source.
- `share/common/src/main/kotlin/com/minekube/connect/share/identity/EndpointIdentityStore.kt` - persistent generated/imported/environment identity.
- `share/common/src/main/kotlin/com/minekube/connect/share/identity/EndpointCredentialValidator.kt` - validation port.
- `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/RandomEndpointNameSource.kt` - normal Connect random-name service with bounded fallback.
- `share/common/src/main/kotlin/com/minekube/connect/share/admission/AdmissionIdentity.kt` - Connect-, Mojang-, and locally-unverified identity types.
- `share/common/src/main/kotlin/com/minekube/connect/share/admission/AdmissionController.kt` - pending/approved decisions and limits.
- `share/common/src/main/kotlin/com/minekube/connect/share/ShareOptions.kt` - game mode, cheats, and guest capacity.
- `share/common/src/main/kotlin/com/minekube/connect/share/ShareState.kt` - state model.
- `share/common/src/main/kotlin/com/minekube/connect/share/ShareCoordinator.kt` - ordered start/stop and cleanup.
- `share/common/src/main/kotlin/com/minekube/connect/share/MinecraftShareBridge.kt` - local bridge port.
- `share/common/src/main/kotlin/com/minekube/connect/share/ConnectShareIngress.kt` - Connect ingress port.

### Shared Fabric runtime

- `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/ConnectShareClient.kt` - singleton client lifecycle.
- `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/ConnectShareRuntime.kt` - constructs Core/Fabric adapters.
- `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/FabricSessionAdmissionGate.kt` - maps Core proposals to `AdmissionController`.
- `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/FabricConnectIngress.kt` - starts/stops the embedded Connect graph.
- `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/ui/ShareViewModel.kt` - screen state and user actions.

### Per-version Fabric adapters

- `share/fabric-1.21.11/src/main/kotlin/com/minekube/connect/share/fabric/v1_21_11/Minecraft12111Bridge.kt`
- `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/ServerConnectionListenerMixin.java`
- `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/IntegratedServerAccessor.java`
- `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/ServerConnectionListenerAccessor.java`
- `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/ConnectionAccessor.java`
- `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/PauseScreenMixin.java`
- `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/ServerLoginPacketListenerMixin.java`
- `share/fabric-26.2/src/main/kotlin/com/minekube/connect/share/fabric/v26_2/Minecraft262Bridge.kt`
- `share/fabric-26.2/src/main/java/com/minekube/connect/share/fabric/v26_2/mixin/ServerConnectionListenerMixin.java`
- `share/fabric-26.2/src/main/java/com/minekube/connect/share/fabric/v26_2/mixin/IntegratedServerAccessor.java`
- `share/fabric-26.2/src/main/java/com/minekube/connect/share/fabric/v26_2/mixin/ServerConnectionListenerAccessor.java`
- `share/fabric-26.2/src/main/java/com/minekube/connect/share/fabric/v26_2/mixin/ConnectionAccessor.java`
- `share/fabric-26.2/src/main/java/com/minekube/connect/share/fabric/v26_2/mixin/PauseScreenMixin.java`
- `share/fabric-26.2/src/main/java/com/minekube/connect/share/fabric/v26_2/mixin/ServerLoginPacketListenerMixin.java`
- Each version module owns `fabric.mod.json`, its mixin JSON, translations, icon, and artifact verification test.

---

### Task 1: Add the isolated multi-version Fabric build

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Modify: `gradle.properties`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `build-logic/src/main/kotlin/Versions.kt`
- Create: `share/common/build.gradle.kts`
- Create: `share/fabric-common/build.gradle.kts`
- Create: `share/fabric-1.21.11/build.gradle.kts`
- Create: `share/fabric-26.2/build.gradle.kts`
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/ShareBuild.kt`
- Create: `share/common/src/test/kotlin/com/minekube/connect/share/BuildPinsTest.kt`

**Interfaces:**
- Consumes: Existing root versioning through `gitVersion()` and existing `:api`/`:core` projects.
- Produces: Gradle projects `:share:common`, `:share:fabric-common`, `:share:fabric-1-21-11`, and `:share:fabric-26-2`; constants `Versions.fabricLoaderVersion`, `fabricApi12111Version`, `fabricApi262Version`, `fabricLanguageKotlinVersion`, `kotlinVersion`, `coroutinesVersion`, `arrowVersion`, and `loomVersion`.

- [x] **Step 1: Write the failing build-pin test**

```kotlin
package com.minekube.connect.share

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildPinsTest {
    @Test
    fun wireProtocolStartsAtOne() {
        assertEquals(1, ShareBuild.WIRE_PROTOCOL)
        assertEquals("connect-share", ShareBuild.MOD_ID)
    }
}
```

Create the production type referenced by the test only after observing the failure:

```kotlin
package com.minekube.connect.share

object ShareBuild {
    const val MOD_ID = "connect-share"
    const val WIRE_PROTOCOL = 1
}
```

- [x] **Step 2: Add the exact Gradle pins and project includes**

Add these constants to `Versions.kt`:

```kotlin
const val loomVersion = "1.17.17"
const val fabricLoaderVersion = "0.19.3"
const val fabricApi12111Version = "0.141.6+1.21.11"
const val fabricApi262Version = "0.156.0+26.2"
const val fabricLanguageKotlinVersion = "1.13.13+kotlin.2.4.10"
const val kotlinVersion = "2.4.10"
const val coroutinesVersion = "1.11.0"
const val arrowVersion = "2.2.3"
const val jvmLibp2pVersion = "1.3.5-RELEASE"
```

Add `maven("https://maven.fabricmc.net/")` to dependency and plugin repositories. Register both Loom plugin IDs at `1.17.17` and Kotlin JVM at `2.4.10`. Include:

```kotlin
include(":share:common")
include(":share:fabric-common")
include(":share:fabric-1-21-11")
include(":share:fabric-26-2")
```

Set the wrapper URL exactly:

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
```

Give the combined Loom-remap and plugin-shadow build enough heap:

```properties
org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=768m
```

- [x] **Step 3: Keep plugin and Fabric conventions separate**

In root `build.gradle.kts`, use Gradle-safe project paths (the directory names
retain dots while Gradle project names use hyphens):

```kotlin
val shareProjectPaths = setOf(
    ":share",
    ":share:common",
    ":share:fabric-common",
    ":share:fabric-1-21-11",
    ":share:fabric-26-2",
)
```

Apply the existing Java-11/Lombok/Shadow conventions only when
`path !in shareProjectPaths`. The common modules apply Kotlin JVM and target
Java 21. The 1.21.11 module applies `net.fabricmc.fabric-loom-remap` and Java
21. The 26.2 module applies `net.fabricmc.fabric-loom` and Java 25. Declare the
Kotlin plugin once on the root with `apply false` so Gradle shares one plugin
classloader across the modules.

The `share/common` dependencies are:

```kotlin
implementation(projects.core)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutinesVersion}")
api(platform("io.arrow-kt:arrow-stack:${Versions.arrowVersion}"))
api("io.arrow-kt:arrow-core")
implementation("io.arrow-kt:arrow-fx-coroutines")
testImplementation(kotlin("test"))
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutinesVersion}")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

The `share/fabric-common` dependencies are:

```kotlin
implementation(projects.core)
implementation(projects.share.common)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutinesVersion}")
implementation(platform("io.arrow-kt:arrow-stack:${Versions.arrowVersion}"))
implementation("io.arrow-kt:arrow-core")
implementation("io.arrow-kt:arrow-fx-coroutines")
implementation("com.squareup.okhttp3:okhttp:4.9.3")
testImplementation(kotlin("test"))
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${Versions.coroutinesVersion}")
testImplementation("com.squareup.okhttp3:mockwebserver:4.9.3")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

Both common modules configure `tasks.test { useJUnitPlatform() }`.

The 1.21.11 dependency block must contain:

```kotlin
minecraft("com.mojang:minecraft:1.21.11")
mappings(loom.officialMojangMappings())
modImplementation("net.fabricmc:fabric-loader:${Versions.fabricLoaderVersion}")
modImplementation("net.fabricmc.fabric-api:fabric-api:${Versions.fabricApi12111Version}")
modImplementation("net.fabricmc:fabric-language-kotlin:${Versions.fabricLanguageKotlinVersion}")
implementation(projects.core)
implementation(projects.share.common)
implementation(projects.share.fabricCommon)
```

The 26.2 block uses `minecraft("com.mojang:minecraft:26.2")`, no mappings
dependency, and ordinary `implementation` dependencies for Fabric Loader,
Fabric API at `Versions.fabricApi262Version`, and Fabric Language Kotlin. The
non-remapping Loom plugin intentionally does not create `modImplementation`.

Loom owns project-local repositories for remapped artifacts, so repository mode
must allow project repositories. Declare Connect Core's non-central runtime
sources (OpenCollab releases and snapshots, jvm-libp2p Cloudsmith, ConsenSys,
and the group-filtered JitPack source) in both Fabric projects so Loom
resolution does not hide the settings repositories.

- [x] **Step 4: Run the new test and both empty mod builds**

Run:

```bash
./gradlew :share:common:test :share:fabric-1-21-11:build :share:fabric-26-2:build
```

Expected: `BuildPinsTest` passes and both Fabric projects produce JAR tasks without changing plugin artifact names.

- [x] **Step 5: Run the existing plugin build**

Run:

```bash
./gradlew build
```

Expected: all existing plugin tests pass under Gradle 9.5.1. Fix only concrete Gradle-9 API errors encountered; retain Java-11 bytecode for `api`, `core`, `spigot`, `velocity`, and `bungee`.

- [x] **Step 6: Commit**

```bash
git add gradle/wrapper/gradle-wrapper.properties settings.gradle.kts build.gradle.kts build-logic/src/main/kotlin/Versions.kt share
git commit -m "build: add multi-version Fabric Share modules"
```

### Task 2: Extract plugin-compatible endpoint token persistence

**Files:**
- Create: `core/src/main/java/com/minekube/connect/identity/EndpointTokenStore.java`
- Create: `core/src/test/java/com/minekube/connect/identity/EndpointTokenStoreTest.java`
- Modify: `core/src/main/java/com/minekube/connect/module/CommonModule.java`
- Modify: `core/src/test/java/com/minekube/connect/module/CommonModuleTest.java`

**Interfaces:**
- Consumes: `Utils.randomSecureString(20)` and Gson.
- Produces: `EndpointTokenStore.load(Path, Map<String,String>)`, `loadOrCreate(Path, Map<String,String>)`, `save(Path,String)`, `generate()`, and `redact(String)`.

- [x] **Step 1: Write failing token-store tests**

Cover these exact cases:

```java
@Test void createsPluginCompatibleTokenJson()
@Test void reusesTheSameToken()
@Test void connectTokenEnvironmentOverridesDisk()
@Test void rejectsBlankAndNonPrefixedTokens()
@Test void atomicallyReplacesToken()
@Test void redactionNeverContainsTheToken()
```

The core assertions are:

```java
assertTrue(token.startsWith("T-"));
assertEquals(token, new Gson().fromJson(Files.readString(file), JsonObject.class).get("token").getAsString());
assertFalse(EndpointTokenStore.redact(token).contains(token));
```

- [x] **Step 2: Run the focused test and observe failure**

Run:

```bash
./gradlew :core:test --tests com.minekube.connect.identity.EndpointTokenStoreTest
```

Expected: compilation fails because `EndpointTokenStore` does not exist.

- [x] **Step 3: Implement the store**

`EndpointTokenStore` must:

```java
public final class EndpointTokenStore {
    public static final String ENV_TOKEN = "CONNECT_TOKEN";

    public Optional<String> load(Path tokenFile, Map<String, String> environment) throws IOException;
    public String loadOrCreate(Path tokenFile, Map<String, String> environment) throws IOException;
    public void save(Path tokenFile, String token) throws IOException;
    public String generate();
    public static String redact(String token);
}
```

`save` writes `{"token":"T-AAAAAAAAAAAAAAAAAAAA"}` to a sibling temporary file, applies owner read/write permissions when POSIX permissions are supported, then moves with `ATOMIC_MOVE` and `REPLACE_EXISTING`, falling back to `REPLACE_EXISTING` only when atomic moves are unsupported. `load` validates the environment or disk value before returning it.

- [x] **Step 4: Make CommonModule use the shared store**

Replace the private `CommonModule.Token` class with an injected/provider-created `EndpointTokenStore` and:

```java
return endpointTokenStore.loadOrCreate(
        dataDirectory.resolve("token.json"),
        System.getenv());
```

Keep the existing `CommonModuleTest.connectTokenIsPersistedForAllConnectClients` green.

- [x] **Step 5: Run token and core tests**

Run:

```bash
./gradlew :core:test --tests com.minekube.connect.identity.EndpointTokenStoreTest --tests com.minekube.connect.module.CommonModuleTest
```

Expected: all focused tests pass.

- [x] **Step 6: Commit**

```bash
git add core/src/main/java/com/minekube/connect/identity core/src/test/java/com/minekube/connect/identity core/src/main/java/com/minekube/connect/module/CommonModule.java core/src/test/java/com/minekube/connect/module/CommonModuleTest.java
git commit -m "refactor: share endpoint token persistence"
```

### Task 3: Persist, import, validate, and roll back endpoint identities

**Files:**
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/identity/EndpointIdentity.kt`
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/identity/EndpointIdentityStore.kt`
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/identity/EndpointCredentialValidator.kt`
- Create: `share/common/src/test/kotlin/com/minekube/connect/share/identity/EndpointIdentityStoreTest.kt`
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/WatchEndpointCredentialValidator.kt`
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/RandomEndpointNameSource.kt`
- Create: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/WatchEndpointCredentialValidatorTest.kt`
- Create: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/RandomEndpointNameSourceTest.kt`

**Interfaces:**
- Consumes: `EndpointTokenStore`, OkHttp `WebSocket`, and the existing watch endpoint contract.
- Produces:

```kotlin
enum class CredentialSource { GENERATED, IMPORTED, ENVIRONMENT }
data class EndpointIdentity(
    val endpoint: String,
    val token: String,
    val endpointSource: CredentialSource,
    val tokenSource: CredentialSource,
)
fun interface EndpointNameSource {
    suspend fun create(): String
}
fun interface EndpointCredentialValidator {
    suspend fun validate(
        identity: EndpointIdentity,
    ): Either<CredentialValidationError, Unit>
}
sealed interface CredentialValidationError {
    val safeMessage: String
    data class InvalidInput(override val safeMessage: String) : CredentialValidationError
    data class Rejected(override val safeMessage: String) : CredentialValidationError
    data class Network(override val safeMessage: String) : CredentialValidationError
    data class ManagedByEnvironment(
        val fields: NonEmptyList<String>,
        override val safeMessage: String,
    ) : CredentialValidationError
}
```

- [x] **Step 1: Write the identity-store tests**

Tests must prove:

```kotlin
@Test fun `one generated identity survives reload and world changes`()
@Test fun `environment overrides are resolved per field`()
@Test fun `dashboard import commits endpoint and token only after validation`()
@Test fun `bad token leaves prior identity byte-for-byte intact`()
@Test fun `cancelled and failed validation leave prior identity intact`()
@Test fun `plugin token json can be imported`()
@Test fun `reset is explicit and creates one replacement identity`()
@Test fun `logs and toString never contain token`()
@Test fun `second file failure restores the prior identity`()
@Test fun `interrupted transaction rolls back on next load`()
```

Use a deterministic `EndpointNameSource { "amber-fox" }` and token source returning `T-AAAAAAAAAAAAAAAAAAAA`.

- [x] **Step 2: Run and observe the missing-type failure**

Run:

```bash
./gradlew :share:common:test --tests com.minekube.connect.share.identity.EndpointIdentityStoreTest
```

Expected: compilation fails on `EndpointIdentityStore`.

- [x] **Step 3: Implement exact persistence semantics**

`EndpointIdentityStore` has this constructor and public API:

```kotlin
class EndpointIdentityStore(
    private val directory: Path,
    private val environment: Map<String, String>,
    private val endpointNames: EndpointNameSource,
    private val tokenStore: EndpointTokenStore,
) {
    suspend fun currentOrCreate(): EndpointIdentity
    suspend fun import(
        endpoint: String,
        token: String,
        validator: EndpointCredentialValidator,
    ): Either<CredentialValidationError, EndpointIdentity>
    suspend fun importTokenFile(
        endpoint: String,
        tokenFile: Path,
        validator: EndpointCredentialValidator,
    ): Either<CredentialValidationError, EndpointIdentity>
    suspend fun resetConfirmed(): Either<CredentialValidationError, EndpointIdentity>
}
```

Use `config.json` with:

```json
{"endpoint":"amber-fox","endpointSource":"IMPORTED","tokenSource":"IMPORTED"}
```

Validate endpoint names with `^[a-z0-9][a-z0-9-]{2,62}$`. Treat tokens as secrets and override `EndpointIdentity.toString()` to print `token=<redacted>`. Import writes neither file until validation returns `Either.Right(Unit)`, then atomically replaces token first and config second while retaining backups until both moves succeed. Restore both backups when the second move fails.

Use Arrow `either`, `ensure`, `ensureNotNull`, `bind`, and `NonEmptyList` for
the validation workflow and its typed failures. Environment management is
tracked independently for endpoint and token so a field supplied by
`CONNECT_ENDPOINT` or `CONNECT_TOKEN` is never silently overwritten.

Before either move, write `identity-transaction.json` containing the old and
new endpoint names plus both backup and staged file names. `currentOrCreate()`
calls `recoverInterruptedTransaction()` before reading identity files. When
the journal exists without a committed marker, restore both backups, or remove
both partially created files when no prior identity existed, then delete the
journal. When the committed marker is durable, retain the new pair and only
clean staged and backup files. Delete backups and the journal only after both
final files are durable.

- [x] **Step 4: Write validator tests against MockWebServer**

Assert that a validation request sends:

```text
Authorization: Bearer T-AAAAAAAAAAAAAAAAAAAA
Connect-Endpoint: amber-fox
Connect-Platform: Fabric
```

The WebSocket listener must close immediately after HTTP 101 and reject any
binary `WatchResponse` proposal without creating a local tunnel. HTTP 401
returns a sanitized `CredentialValidationError.Rejected`; transport failure
and timeout return `CredentialValidationError.Network`. Caller cancellation
must remain cancellation.

- [x] **Step 5: Implement the Watch validator**

Expose:

```kotlin
class WatchEndpointCredentialValidator(
    private val client: OkHttpClient,
    private val watchUrl: HttpUrl,
    private val timeout: Duration = 10.seconds,
) : EndpointCredentialValidator
```

The coroutine resumes exactly once using an atomic completion guard, cancels the WebSocket on coroutine cancellation, and never includes endpoint tokens in exceptions.

Implement `RandomEndpointNameSource` with a five-second OkHttp timeout against
`https://randomname.minekube.net`. Accept only the endpoint-name pattern from
Step 3. On timeout, non-200, empty body, or invalid body, return five lowercase
letters from `SecureRandom`; do not fail identity creation and do not include
network response bodies in logs.

- [x] **Step 6: Run focused tests**

Run:

```bash
./gradlew :share:common:test :share:fabric-common:test --tests '*EndpointIdentityStoreTest' --tests '*WatchEndpointCredentialValidatorTest' --tests '*RandomEndpointNameSourceTest'
```

Expected: identity and validation tests pass.

- [x] **Step 7: Commit**

```bash
git add share/common/src/main/kotlin/com/minekube/connect/share/identity share/common/src/test/kotlin/com/minekube/connect/share/identity share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/WatchEndpointCredentialValidator.kt share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/RandomEndpointNameSource.kt share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/WatchEndpointCredentialValidatorTest.kt share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/RandomEndpointNameSourceTest.kt
git commit -m "feat: persist and import Share endpoint identities"
```

### Task 4: Add host admission for Connect, Mojang, and offline identities

**Files:**
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/admission/AdmissionIdentity.kt`
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/admission/AdmissionController.kt`
- Create: `share/common/src/test/kotlin/com/minekube/connect/share/admission/AdmissionControllerTest.kt`

**Interfaces:**
- Produces:

```kotlin
sealed interface AdmissionIdentity {
    val name: String
    val uuid: UUID

    data class Authenticated(
        override val name: String,
        override val uuid: UUID,
        val source: AuthSource,
    ) : AdmissionIdentity

    data class UnverifiedOffline(
        override val name: String,
        override val uuid: UUID,
        val connectionId: String,
        val ingress: Ingress,
    ) : AdmissionIdentity
}

enum class AuthSource { CONNECT, MOJANG }
enum class Ingress { CONNECT, DIRECT_LAN, DIRECT_INTERNET }
enum class AdmissionAnswer { ALLOW, DENY, TIMEOUT, STOPPED, CAPACITY }
```

- [x] **Step 1: Write failing admission tests**

Cover:

```kotlin
@Test fun `authenticated UUID approval is reused only during current share`()
@Test fun `offline reconnect with copied name requires a new approval`()
@Test fun `duplicate live requests share one decision`()
@Test fun `seventeenth pending request is rejected`()
@Test fun `request expires after thirty seconds`()
@Test fun `stop resolves all pending requests and clears approvals`()
@Test fun `capacity rejects before adding a pending card`()
```

Use `kotlinx.coroutines.test.runTest` and a test scheduler for the 30-second timeout.

- [x] **Step 2: Run and observe failure**

Run:

```bash
./gradlew :share:common:test --tests com.minekube.connect.share.admission.AdmissionControllerTest
```

Expected: missing admission types.

- [x] **Step 3: Implement AdmissionController**

Expose:

```kotlin
class AdmissionController(
    private val scope: CoroutineScope,
    private val timeout: Duration = 30.seconds,
    private val maxPending: Int = 16,
    private val connectedCount: () -> Int,
    private val maxGuests: () -> Int,
) {
    val pending: StateFlow<List<PendingAdmission>>
    suspend fun request(identity: AdmissionIdentity): AdmissionAnswer
    fun answer(requestId: UUID, allow: Boolean)
    fun resetShare()
}
```

Key authenticated approvals by UUID. Key unverified requests by `connectionId`. Never key offline approval by name or deterministic offline UUID. Complete deferred results outside the controller mutex. `resetShare()` returns `STOPPED` to pending callers and clears remembered authenticated UUIDs.

- [x] **Step 4: Run tests**

Run:

```bash
./gradlew :share:common:test --tests com.minekube.connect.share.admission.AdmissionControllerTest
```

Expected: all seven cases pass.

- [x] **Step 5: Commit**

```bash
git add share/common/src/main/kotlin/com/minekube/connect/share/admission share/common/src/test/kotlin/com/minekube/connect/share/admission
git commit -m "feat: add Share host admission policy"
```

### Task 5: Gate Connect proposals before opening tunnels

**Files:**
- Create: `core/src/main/java/com/minekube/connect/watch/SessionAdmissionGate.java`
- Create: `core/src/main/java/com/minekube/connect/watch/SessionAdmissionDecision.java`
- Create: `core/src/main/java/com/minekube/connect/watch/AllowAllSessionAdmissionGate.java`
- Modify: `core/src/main/java/com/minekube/connect/register/WatcherRegister.java`
- Modify: `core/src/main/java/com/minekube/connect/module/CommonModule.java`
- Modify: `core/src/test/java/com/minekube/connect/register/WatcherRegisterTest.java`
- Create: `core/src/test/java/com/minekube/connect/watch/AllowAllSessionAdmissionGateTest.java`

**Interfaces:**
- Consumes: `SessionProposal`.
- Produces:

```java
public interface SessionAdmissionGate {
    CompletionStage<SessionAdmissionDecision> request(SessionProposal proposal);
}

public final class SessionAdmissionDecision {
    public static SessionAdmissionDecision allow();
    public static SessionAdmissionDecision deferToLocalLogin();
    public static SessionAdmissionDecision deny(String safeMessage);
    public boolean isAllowed();
    public boolean isDeferredToLocalLogin();
    public String getSafeMessage();
}
```

- [x] **Step 1: Add failing WatcherRegister tests**

Add tests that hold a `CompletableFuture<SessionAdmissionDecision>` and assert:

```java
verifyNoInteractions(tunneler);
assertEquals(0, localSessionConnections.get());
```

before completion. On `allow()`, assert one `prepare` and one local connection. On deny, timeout, exceptional completion, or watcher stop, assert proposal rejection and zero tunnel work.

- [x] **Step 2: Run and observe failure**

Run:

```bash
./gradlew :core:test --tests com.minekube.connect.register.WatcherRegisterTest
```

Expected: compilation fails because the gate does not exist.

- [x] **Step 3: Implement the default gate and WatcherRegister sequencing**

Use Guice `OptionalBinder` in `CommonModule`: set
`AllowAllSessionAdmissionGate` as the default `SessionAdmissionGate`, and let
the Fabric platform module set the actual binding without a duplicate-binding
error. In `WatcherRegister.WatcherImpl.onProposal`, call the gate after
structural validation and before `tunneler.prepare`. Continue on the existing
watcher executor only when:

```java
started.get()
    && proposal.getState() == State.ACCEPTED
    && (decision.isAllowed() || decision.isDeferredToLocalLogin())
```

Treat `deferToLocalLogin()` as permission to open the bounded tunnel without marking the player admitted; the Fabric login hook owns the later decision. Map deny/exception to a `PERMISSION_DENIED` or `INTERNAL` `google.rpc.Status` with only the safe message. Never throw asynchronous gate failures on OkHttp's callback thread.

- [x] **Step 4: Run Core tests**

Run:

```bash
./gradlew :core:test --tests com.minekube.connect.register.WatcherRegisterTest --tests com.minekube.connect.watch.AllowAllSessionAdmissionGateTest
```

Expected: focused tests pass and existing plugin behavior remains immediate-allow.

- [x] **Step 5: Commit**

```bash
git add core/src/main/java/com/minekube/connect/watch core/src/main/java/com/minekube/connect/register/WatcherRegister.java core/src/main/java/com/minekube/connect/module/CommonModule.java core/src/test/java/com/minekube/connect/watch core/src/test/java/com/minekube/connect/register/WatcherRegisterTest.java
git commit -m "feat: gate Connect sessions before tunneling"
```

### Task 6: Implement the Share state machine and cleanup contract

**Files:**
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/ShareOptions.kt`
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/ShareState.kt`
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/MinecraftShareBridge.kt`
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/ConnectShareIngress.kt`
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/ShareCoordinator.kt`
- Create: `share/common/src/test/kotlin/com/minekube/connect/share/ShareCoordinatorTest.kt`

**Interfaces:**
- Produces:

```kotlin
data class ShareOptions(
    val gameMode: ShareGameMode,
    val allowCheats: Boolean,
    val maxGuests: Int = 8,
)

data class LocalShareTarget(
    val address: SocketAddress,
    val close: suspend () -> Unit,
)

interface MinecraftShareBridge {
    suspend fun open(options: ShareOptions): LocalShareTarget
}

interface ConnectShareIngress {
    suspend fun start(identity: EndpointIdentity, target: SocketAddress): ConnectShareHandle
}

data class ConnectShareHandle(
    val endpoint: String,
    val publicAddress: String,
    val close: suspend () -> Unit,
)
```

- [x] **Step 1: Write state and cleanup tests**

Prove:

```kotlin
@Test fun `start orders bridge before ingress`()
@Test fun `connect failure closes bridge and enters failed`()
@Test fun `stop closes ingress then bridge and clears admission`()
@Test fun `stop is idempotent`()
@Test fun `world replacement stops active share`()
@Test fun `capacity outside one through sixteen is rejected`()
@Test fun `start cancellation releases the bridge and remains cancellation`()
```

- [x] **Step 2: Run and observe missing production types**

Run:

```bash
./gradlew :share:common:test --tests com.minekube.connect.share.ShareCoordinatorTest
```

Expected: compilation failure.

- [x] **Step 3: Implement the coordinator**

`ShareState` is:

```kotlin
sealed interface ShareState {
    data object Idle : ShareState
    data object Starting : ShareState
    data class Sharing(val endpoint: String, val address: String) : ShareState
    data object Stopping : ShareState
    data class Failed(val safeMessage: String) : ShareState
}
```

`ShareCoordinator.start` runs under a mutex, creates the bridge, loads identity,
starts Connect, and publishes `Sharing`. It returns
`Either<ShareLifecycleError, ShareState.Sharing>`. Model the bridge and ingress
as one Arrow `Resource`; the coordinator's carefully bounded `allocate`
interop keeps that resource alive across UI events while explicitly releasing
partially acquired resources on every failed or cancelled start.

`stop` snapshots the release handle under the mutex, publishes `Stopping`,
releases the Arrow resource (ingress then bridge), resets admission, then
publishes `Idle`. Arrow runs every finalizer and combines cleanup failures.
Return a typed `StopFailed`, report only a fixed safe summary, and never convert
coroutine cancellation into a domain failure.

- [x] **Step 4: Run tests and commit**

Run:

```bash
./gradlew :share:common:test
```

Expected: all common tests pass.

Commit:

```bash
git add share/common
git commit -m "feat: add Connect Share lifecycle"
```

### Task 7: Create an embedded Connect runtime for Fabric

**Files:**
- Modify: `core/src/main/java/com/minekube/connect/config/ConnectConfig.java`
- Modify: `core/src/main/java/com/minekube/connect/ConnectPlatform.java`
- Create: `core/src/test/java/com/minekube/connect/EmbeddedConnectPlatformTest.java`
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/FabricSessionAdmissionGate.kt`
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/FabricLocalLoginAdmission.kt`
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/FabricConnectIngress.kt`
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/FabricPlatformUtils.kt`
- Create: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/FabricSessionAdmissionGateTest.kt`
- Create: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/FabricConnectIngressTest.kt`

**Interfaces:**
- Consumes: `EndpointIdentity`, `AdmissionController`, `PlatformInjector`, and `ConnectPlatform`.
- Produces: `ConnectConfig.embedded(String endpoint, boolean allowOfflineModePlayers)`, `ConnectPlatform.initEmbedded(Path dataDirectory, ConnectConfig config, ConfigHolder configHolder, PacketHandlers packetHandlers)`, `FabricSessionAdmissionGate`, `FabricLocalLoginAdmission`, and `FabricConnectIngress`.

- [x] **Step 1: Write failing embedded-platform tests**

Assert:

```java
ConnectConfig config = ConnectConfig.embedded("amber-fox", true);
assertEquals("amber-fox", config.getEndpoint());
assertEquals(Boolean.TRUE, config.getAllowOfflineModePlayers());
```

Create a fake `PlatformInjector` and assert `initEmbedded` never creates `config.yml`, starts Watch only after injector success, and closes Watch, libp2p, tunnels, and local channel once.

- [x] **Step 2: Add the embedded Core entry point**

Add:

```java
public static ConnectConfig embedded(String endpoint, boolean allowOfflineModePlayers)
```

and:

```java
public void initEmbedded(
        Path dataDirectory,
        ConnectConfig config,
        ConfigHolder configHolder,
        PacketHandlers packetHandlers)
```

Share the common initialization tail with the existing `init`; do not change plugin config loading.

- [x] **Step 3: Implement the Kotlin admission adapter**

`FabricSessionAdmissionGate.request` maps:

- non-passthrough Connect profile → `AdmissionIdentity.Authenticated(name, uuid, AuthSource.CONNECT)`;
- passthrough Connect proposal → `SessionAdmissionDecision.deferToLocalLogin()`.

Map `ALLOW` to `SessionAdmissionDecision.allow()` and every other non-deferred answer to a safe denial. The returned `CompletionStage` is cancelled when the share stops.

`FabricLocalLoginAdmission` exposes:

```kotlin
suspend fun request(
    name: String,
    uuid: UUID,
    connectionId: String,
    minecraftAuthenticated: Boolean,
): AdmissionAnswer
```

It maps an authenticated profile to
`AdmissionIdentity.Authenticated(name, uuid, AuthSource.MOJANG)` and a locally
offline profile to
`AdmissionIdentity.UnverifiedOffline(name, uuid, connectionId,
Ingress.CONNECT)`. It completes before vanilla moves the connection into
configuration/play state.

- [x] **Step 4: Implement FabricConnectIngress**

Build a private Guice injector from `ServerCommonModule`, a Fabric platform module providing logger/platform metadata/injector/gate, `ConfigLoadedModule(config)`, `Libp2pEndpointModule`, and `WatcherModule`. Set:

```text
platformName = Fabric
serverImplementationName = Minecraft integrated server
authType = OFFLINE
allowOfflineModePlayers = true
```

Use the already persisted `token.json`; do not generate or write credentials
inside `start`. Read and compare the effective stored/environment token with
the already resolved `EndpointIdentity` before constructing the runtime.
Return the `ConnectShareHandle` defined in Task 6:

```kotlin
ConnectShareHandle(
    endpoint = identity.endpoint,
    publicAddress = "${identity.endpoint}.play.minekube.net",
    close = { platform.disable() },
)
```

where `publicAddress` is `<endpoint>.play.minekube.net`.

- [x] **Step 5: Run focused and Core regression tests**

Run:

```bash
./gradlew :core:test --tests com.minekube.connect.EmbeddedConnectPlatformTest :share:fabric-common:test
```

Expected: embedded lifecycle and admission mapping pass.

- [x] **Step 6: Commit**

```bash
git add core/src/main/java/com/minekube/connect/config/ConnectConfig.java core/src/main/java/com/minekube/connect/ConnectPlatform.java core/src/test/java/com/minekube/connect/EmbeddedConnectPlatformTest.java share/fabric-common
git commit -m "feat: add embedded Fabric Connect ingress"
```

### Task 8: Implement the 1.21.11 private integrated-server bridge

**Files:**
- Create: `share/fabric-1.21.11/src/main/kotlin/com/minekube/connect/share/fabric/v1_21_11/Minecraft12111Bridge.kt`
- Create: `share/fabric-1.21.11/src/main/kotlin/com/minekube/connect/share/fabric/v1_21_11/CapturedServerTransport.kt`
- Create: `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/ServerConnectionListenerMixin.java`
- Create: `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/IntegratedServerAccessor.java`
- Create: `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/ServerConnectionListenerAccessor.java`
- Create: `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/ConnectionAccessor.java`
- Create: `share/fabric-1.21.11/src/main/java/com/minekube/connect/share/fabric/v1_21_11/mixin/ServerLoginPacketListenerMixin.java`
- Create: `share/fabric-1.21.11/src/test/kotlin/com/minekube/connect/share/fabric/v1_21_11/Minecraft12111BridgeTest.kt`

**Interfaces:**
- Consumes: `IntegratedServer.publishServer`, `ServerConnectionListener.startTcpServerListener`, `LocalServerChannelWrapper`, and Connect channel attributes.
- Produces: `Minecraft12111Bridge : MinecraftShareBridge`.

- [x] **Step 1: Generate and inspect exact 1.21.11 sources**

Run:

```bash
./gradlew :share:fabric-1-21-11:genSources
```

Confirm the official mapped members used by this task exist:

```text
IntegratedServer.publishServer(GameType, boolean, int)
IntegratedServer.publishedPort
MinecraftServer.getConnection()
ServerConnectionListener.startTcpServerListener(InetAddress, int)
ServerConnectionListener.channels
```

If Loom reports a different official member name, update only the adapter and record the exact resolved name in the mixin JSON; do not use broad reflection.

- [x] **Step 2: Write the bridge test before mixins**

Use a fake captured transport and assert:

```kotlin
assertTrue(boundAddress.address.isLoopbackAddress)
assertTrue(localAddress is LocalAddress)
assertEquals(-1, publishedPortAfterClose)
assertEquals(0, capturedListenerCountAfterClose)
```

Opening twice after close must succeed; opening while active must fail without adding a second listener.

- [x] **Step 3: Capture vanilla's child initializer and force loopback**

`ServerConnectionListenerMixin` uses `@ModifyArg` on `ServerBootstrap.childHandler` and `ServerBootstrap.group` to capture the exact initializer/group, and a second `@ModifyArg`/method argument modification so the active Share publish calls:

```java
InetAddress.getLoopbackAddress()
```

It must leave ordinary vanilla publishing unchanged unless `CapturedServerTransport.isShareStartArmed()` is true.

`ServerConnectionListenerAccessor` exposes the listener
`List<ChannelFuture>`. `IntegratedServerAccessor` exposes mutable
`publishedPort`. `ConnectionAccessor` exposes the exact Netty `Channel` held by
Minecraft's `Connection` so the login mixin can read Connect's channel
attribute without reflection.

- [x] **Step 4: Bind the local channel and implement stop**

After `publishServer`, identify exactly one newly added loopback `ChannelFuture`. Bind:

```kotlin
ServerBootstrap()
    .channel(LocalServerChannelWrapper::class.java)
    .childHandler(captured.childInitializer)
    .group(DefaultEventLoopGroup(0, DefaultThreadFactory("Connect Share local")))
    .localAddress(LocalAddress.ANY)
    .bind()
    .syncUninterruptibly()
```

On close, stop Connect first through the coordinator, close/remove the local future, close/remove the captured loopback future, set `publishedPort = -1`, and shut down the dedicated local event loop gracefully.

- [x] **Step 5: Inject Connect-authenticated login profiles**

`ServerLoginPacketListenerMixin` reads `ConnectAttributes.CONNECT_PLAYER` from the connection channel. For non-passthrough sessions it converts the Connect profile to Mojang `GameProfile`, preserves signed properties, bypasses a second Mojang encryption/authentication round trip, and enters vanilla's verified-login continuation.

For passthrough Connect sessions it lets vanilla resolve online/offline login, then pauses before configuration/play state, calls `FabricLocalLoginAdmission`, and continues only on `ALLOW`. Deny, timeout, disconnect, or share stop closes the connection. Ordinary LAN channels execute untouched vanilla code.

- [x] **Step 6: Run adapter tests and a headless launch smoke**

Run:

```bash
./gradlew :share:fabric-1-21-11:test :share:fabric-1-21-11:runServer --args='nogui'
```

Expected: unit tests pass; the dev server reaches startup with every mixin applied. Terminate the smoke after the ready log and confirm no mixin application error.

- [ ] **Step 7: Commit**

```bash
git add share/fabric-1.21.11
git commit -m "feat: bridge Connect into 1.21.11 singleplayer"
```

### Task 9: Implement the 26.2 adapter and assert cross-version parity

**Files:**
- Create: matching `v26_2` bridge and mixin files under `share/fabric-26.2/src/main`
- Create: `share/fabric-26.2/src/test/kotlin/com/minekube/connect/share/fabric/v26_2/Minecraft262BridgeTest.kt`
- Create: `share/common/src/test/kotlin/com/minekube/connect/share/AdapterContractTest.kt`

**Interfaces:**
- Consumes: the same `MinecraftShareBridge` contract and unobfuscated 26.2 Minecraft classes.
- Produces: `Minecraft262Bridge : MinecraftShareBridge` with behavior identical to Task 8.

- [x] **Step 1: Generate 26.2 sources and verify names**

Run:

```bash
./gradlew :share:fabric-26-2:genSources
```

Use the unobfuscated 26.2 member names reported by Loom. Keep all changed names inside `v26_2`; do not add Minecraft types to `share/common` or `share/fabric-common`.

- [x] **Step 2: Write parity tests**

Run the same contract fixture against both fake adapters:

```kotlin
fun bridgeContract(factory: () -> MinecraftShareBridgeHarness) {
    val first = factory().openAndClose()
    val second = factory().openAndClose()
    assertTrue(first.boundAddress.address.isLoopbackAddress)
    assertTrue(second.boundAddress.address.isLoopbackAddress)
    assertEquals(-1, second.publishedPortAfterClose)
}
```

- [x] **Step 3: Implement the 26.2 bridge and mixins**

Repeat the explicit loopback, captured initializer, `LocalServerChannelWrapper`, login profile injection, and exact close semantics with 26.2 official names. The behavioral code remains Kotlin; Java mixins only expose/capture Minecraft internals.

- [x] **Step 4: Build and smoke both versions**

Run:

```bash
./gradlew :share:fabric-1-21-11:test :share:fabric-26-2:test :share:fabric-1-21-11:build :share:fabric-26-2:build
```

Expected: both artifacts compile and parity tests pass.

- [x] **Step 5: Commit**

```bash
git add share/fabric-26.2 share/common/src/test/kotlin/com/minekube/connect/share/AdapterContractTest.kt
git commit -m "feat: bridge Connect into 26.2 singleplayer"
```

### Task 10: Add the pause-menu sharing and approval UI

**Files:**
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/ConnectShareClient.kt`
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/ConnectShareRuntime.kt`
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/ui/ShareViewModel.kt`
- Create: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/ui/ShareViewModelTest.kt`
- Create: per-version `PauseScreenMixin.java`, `ShareSetupScreen.kt`, `ShareStatusScreen.kt`, and `EndpointIdentityScreen.kt`
- Create: per-version `assets/connect-share/lang/en_us.json`
- Create: per-version `assets/connect-share/lang/de_de.json`
- Create: per-version `fabric.mod.json` and mixin JSON

**Interfaces:**
- Consumes: `ShareCoordinator.state`, `AdmissionController.pending`, and `EndpointIdentityStore`.
- Produces: the host's complete start/stop/copy/import/approve/deny experience.

- [ ] **Step 1: Write view-model tests**

Prove:

```kotlin
@Test fun `start is disabled without a world or while starting`()
@Test fun `capacity is clamped to one through sixteen`()
@Test fun `token is cleared from mutable UI state after successful import`()
@Test fun `environment managed fields cannot be edited`()
@Test fun `allow and deny target the exact pending request`()
@Test fun `leaving a world invokes stop exactly once`()
```

- [ ] **Step 2: Implement ConnectShareClient lifecycle**

Register the Fabric client initializer, create one runtime under:

```text
FabricLoader.getInstance().configDir/minekube-connect-share
```

Listen for client disconnect/game shutdown/integrated-server replacement and call `ShareCoordinator.stop()`. Never stop merely because a screen closes.

- [ ] **Step 3: Implement exact screens**

The pause menu button is **Share with friends** when idle and **Sharing with friends** when active.

The setup screen contains game mode, cheats, max guests default 8, the direct
internet option, and **Share with friends**.

The status screen contains:

- stable `<endpoint>.play.minekube.net` with copy button;
- state line;
- pending cards showing name, UUID, **Connect authenticated**, **Verified online**, or **Unverified offline**;
- **Allow**, **Deny**, and **Stop sharing with friends**;
- **Advanced settings…** link.

The identity screen contains:

- endpoint name;
- masked credential source;
- **Import token.json…**;
- endpoint field plus masked token field;
- `token.json` chooser;
- **Validate and save**;
- warned **Reset endpoint identity…**.

Never render or retain a successful token value.

- [ ] **Step 4: Add metadata and translations**

Each `fabric.mod.json` declares client environment, Kotlin entrypoint, exact Minecraft version, Java floor, Fabric Loader, Fabric API, and Fabric Language Kotlin. Use the mod ID `connect-share`.

- [ ] **Step 5: Run tests and compile UI**

Run:

```bash
./gradlew :share:fabric-common:test :share:fabric-1-21-11:build :share:fabric-26-2:build
```

Expected: view-model tests pass and both UI adapters compile.

- [ ] **Step 6: Commit**

```bash
git add share/fabric-common share/fabric-1.21.11 share/fabric-26.2
git commit -m "feat: add Connect Share host UI"
```

### Task 11: Harden packaged runtime isolation and artifact contents

**Files:**
- Modify: `core/src/main/java/com/minekube/connect/tunnel/p2p/Libp2pRuntimeLoader.java`
- Modify: `build-logic/src/main/kotlin/connect.shadow-conventions.gradle.kts`
- Modify: both Fabric build scripts
- Create: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/SecretRedactionTest.kt`
- Create: `share/fabric-1.21.11/src/test/kotlin/com/minekube/connect/share/fabric/v1_21_11/Fabric12111ArtifactTest.kt`
- Create: `share/fabric-26.2/src/test/kotlin/com/minekube/connect/share/fabric/v26_2/Fabric262ArtifactTest.kt`

**Interfaces:**
- Consumes: existing reflective `Libp2pRuntimeLoader`.
- Produces: self-contained Fabric JARs with no parent-facing duplicate Netty/Kotlin/libp2p classes and a child-only isolated runtime payload.

- [ ] **Step 1: Write failing artifact tests**

Open the remapped JARs and assert:

```text
fabric.mod.json exists
LICENSE exists
connect-share mixin JSON exists
com/minekube/connect/share classes exist
io/libp2p/ does not exist at top level
io/netty/ does not exist at top level
kotlin/ does not exist at top level
META-INF/connect/libp2p-runtime.jar exists
```

Reflect over parent-facing Share/Core types and reject fields, parameters, or return types beginning `io.libp2p.`, isolated `io.netty.`, or isolated `kotlin.`.

- [ ] **Step 2: Package the runtime as a child-only payload**

Build `META-INF/connect/libp2p-runtime.jar` from jvm-libp2p 1.3.5 and its runtime dependencies. Update `Libp2pRuntimeLoader` to extract that resource to a content-hashed temporary file, add it only to `ChildFirstRuntimeClassLoader`, close extracted resources on shutdown, and preserve plugin classpath fallback for development tests.

Merge `:api`, `:core`, `:share:common`, and `:share:fabric-common` into each mod artifact while excluding top-level libp2p/Netty/Kotlin runtime dependencies. Fabric Language Kotlin supplies the parent Kotlin runtime.

- [ ] **Step 3: Add secret scans**

Construct failures containing endpoint tokens, invitations, and direct candidates. Assert captured logs and screen models contain `<redacted>` and do not contain the raw values.

- [ ] **Step 4: Run artifact and isolation verification**

Run:

```bash
./gradlew :core:test --tests '*Libp2pRuntime*' :share:fabric-1-21-11:build :share:fabric-26-2:build :share:fabric-1-21-11:test --tests '*ArtifactTest' :share:fabric-26-2:test --tests '*ArtifactTest'
```

Expected: all isolation and artifact assertions pass.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/minekube/connect/tunnel/p2p/Libp2pRuntimeLoader.java build-logic/src/main/kotlin/connect.shadow-conventions.gradle.kts share
git commit -m "build: isolate Connect Share networking runtime"
```

### Task 12: Add CI gates and complete the singleplayer acceptance pass

**Files:**
- Modify: `.github/workflows/pullrequest.yml`
- Create: `docs/connect-share-testing.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: both remapped Fabric artifacts and all verification tasks.
- Produces: PR CI proof for plugin Java 17/21 plus mod Java 21/25; operator-facing test guide.

- [ ] **Step 1: Add isolated CI jobs**

Keep the existing plugin matrix. Add:

```yaml
share-1-21-11:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
      with:
        fetch-depth: 0
    - uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: "21"
        cache: gradle
    - uses: gradle/actions/setup-gradle@v4
    - run: ./gradlew :share:fabric-1-21-11:build

share-26-2:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
      with:
        fetch-depth: 0
    - uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: "25"
        cache: gradle
    - uses: gradle/actions/setup-gradle@v4
    - run: ./gradlew :share:fabric-26-2:build
```

Archive each remapped mod JAR under a distinct artifact name. Do not add mod files to the plugin release workflow in this plan.

- [ ] **Step 2: Write the manual acceptance guide**

Document exact checks:

1. Create an automatic identity and share twice; endpoint and token remain byte-for-byte identical.
2. Import a dashboard endpoint/token; bad import rolls back, good import preserves its endpoint name.
3. Join 1.21.11 and 26.2 from an unmodified paid Java client through Connect.
4. Join through Connect from a non-paid/offline-mode client.
5. Deny and allow requests; reconnect behavior matches authentication trust.
6. Stop sharing; hostname no longer reaches the world.
7. Start a different world; same endpoint works and no new endpoint record appears.
8. From another LAN device, verify the chosen TCP port is unreachable.
9. Repeat start/stop twice and inspect thread/channel counts for leaks.

- [ ] **Step 3: Run the complete local verification**

Run:

```bash
./gradlew :core:test :share:common:test :share:fabric-common:test :share:fabric-1-21-11:build :share:fabric-26-2:build
./gradlew build
git diff --check
```

Expected: every command exits 0.

- [ ] **Step 4: Inspect artifacts**

Run:

```bash
jar tf share/fabric-1.21.11/build/libs/connect-share-fabric-1.21.11.jar
jar tf share/fabric-26.2/build/libs/connect-share-fabric-26.2.jar
```

Expected: the required metadata, translations, license, Share classes, and isolated runtime payload are present; no top-level duplicate Netty/libp2p/Kotlin packages are present.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/pullrequest.yml docs/connect-share-testing.md README.md
git commit -m "ci: verify Connect Share Fabric artifacts"
```

## Phase Completion Gate

Before starting the direct-P2P plan:

- Both Fabric JARs build on their required JDK.
- Existing `./gradlew build` remains green.
- One endpoint identity is reused across worlds.
- Dashboard credential import is validated and atomic.
- Paid and non-paid vanilla Java clients reach the world through Connect.
- Non-passthrough host admission happens before tunnel creation; passthrough admission happens before world entry.
- Stop/world-exit/game-exit cleanup is idempotent.
- No wildcard/LAN/WAN Minecraft listener is reachable.
- The mod package preserves Core's networking/runtime isolation.
- Epic #83 is updated with the singleplayer slice result and remaining direct-P2P work.
