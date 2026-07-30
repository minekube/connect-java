# Connect Share Pasted LAN Invitation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a pasted Connect Share invitation prefer its already-validated
same-LAN mDNS discovery before falling back to Minekube Connect.

**Architecture:** Keep reconciliation inside `FabricShareBrowser`, which owns
both parsed invitations and the validated discovery snapshot. Derive one
effective LAN address from the explicit UI selection or a discovery with the
same signed `shareId` and `peerId`, then pass that address through the existing
route planner and fallback loop.

**Tech Stack:** Kotlin 2.4.10, Arrow 2.2.3, kotlinx.coroutines, Fabric,
JUnit Platform through Kotlin Test, Gradle.

## Global Constraints

- LAN addresses remain outside copied invitations and logs.
- A discovery match requires both `shareId` and `peerId`.
- The pasted invitation remains the source of the tunnel capability.
- Missing or failed LAN discovery preserves internet-direct and Connect
  fallback.
- The behavior is implemented once in `share/fabric-common` for Minecraft
  1.21.11 and 26.2.
- Use Arrow where it supplies an appropriate abstraction, following
  `share/AGENTS.md`; keep the existing nullable Fabric interop parameter.

---

### Task 1: Reconcile Pasted Invitations With Validated LAN Discovery

**Files:**
- Modify: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/FabricShareBrowserTest.kt`
- Modify: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/FabricShareBrowser.kt`

**Interfaces:**
- Consumes: `FabricShareBrowser.discovered`, `SignedShareInvite.payload`,
  `DiscoveredLanShare.lanAddress`, and the existing nullable
  `FabricShareBrowser.join(..., lanAddress: String?, ...)` parameter.
- Produces: private
  `matchingLanAddress(invitation: SignedShareInvite): String?` and an effective
  LAN address used by `TransportSelector.plan` and `openDirect`.

- [ ] **Step 1: Write the failing regression and identity-match tests**

Add tests that start discovery, inject signed nearby shares, and call `join`
as the paste path does with `lanAddress = null`:

```kotlin
@Test
fun `pasted invitation uses its matching discovered LAN address`() = runTest {
    val node = FakeGuestNode()
    val browser = browser(node)
    browser.start()
    val invitation = invitation()
    node.discover(
        DirectP2pDiscoveredShare(
            "Robin's World",
            PEER_ID,
            LAN_ADDRESS,
            invitation,
        ),
    )

    val result = browser.join(
        invitationUri = invitation,
        lanAddress = null,
        internetOptIn = false,
        authMode = DirectP2pAuthMode.OFFLINE,
    )

    val target = assertIs<Either.Right<GuestJoinTarget.Direct>>(result).value
    assertEquals(ShareRoute.DIRECT_LAN, target.route)
    assertEquals(listOf(LAN_ADDRESS), node.openedAddresses)
    target.close()
    browser.close()
}

@Test
fun `pasted invitation ignores discovery with a different peer`() = runTest {
    val node = FakeGuestNode()
    val browser = browser(node)
    browser.start()
    val otherPeer = "12D3KooWOther"
    node.discover(
        DirectP2pDiscoveredShare(
            "Other World",
            otherPeer,
            lanAddress(otherPeer),
            invitation(peerId = otherPeer),
        ),
    )

    val result = browser.join(
        invitationUri = invitation(),
        lanAddress = null,
        internetOptIn = false,
        authMode = DirectP2pAuthMode.OFFLINE,
    )

    assertIs<Either.Right<GuestJoinTarget.Connect>>(result)
    assertTrue(node.openedAddresses.isEmpty())
    browser.close()
}

@Test
fun `pasted invitation ignores discovery with a different share`() = runTest {
    val node = FakeGuestNode()
    val browser = browser(node)
    browser.start()
    val otherShare = UUID.fromString("72a5d404-0ef9-48bc-882b-a2ec896afbe5")
    node.discover(
        DirectP2pDiscoveredShare(
            "Other World",
            PEER_ID,
            LAN_ADDRESS,
            invitation(shareId = otherShare),
        ),
    )

    val result = browser.join(
        invitationUri = invitation(),
        lanAddress = null,
        internetOptIn = false,
        authMode = DirectP2pAuthMode.OFFLINE,
    )

    assertIs<Either.Right<GuestJoinTarget.Connect>>(result)
    assertTrue(node.openedAddresses.isEmpty())
    browser.close()
}
```

Make the invitation fixture accept identity parameters and generate matching
direct candidates:

```kotlin
private fun invitation(
    shareId: UUID = SHARE_ID,
    peerId: String = PEER_ID,
): String {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val payload = ShareInvitePayload(
        wireVersion = ShareInviteCodec.WIRE_VERSION,
        shareId = shareId,
        expiresAtEpochMillis = NOW + 60_000,
        connectAddress = "amber-fox.play.minekube.net",
        peerId = peerId,
        internetDirectEnabled = true,
        directCandidates = listOf(internetAddress(peerId)),
        capability = CAPABILITY,
    )
    val unsigned = ShareInviteCodec.unsignedBytes(payload, pair.public.encoded)
    val signature = Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(unsigned)
        sign()
    }
    return ShareInviteCodec.encode(
        SignedShareInvite(payload, pair.public.encoded, signature),
    )
}

private fun lanAddress(peerId: String) =
    "/ip4/192.168.1.20/tcp/4001/p2p/$peerId"

private fun internetAddress(peerId: String) =
    "/ip6/2001:db8::20/tcp/4001/p2p/$peerId"
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```sh
./gradlew :share:fabric-common:test \
  --tests com.minekube.connect.share.fabric.FabricShareBrowserTest
```

Expected: FAIL in
`pasted invitation uses its matching discovered LAN address` because the
result is `GuestJoinTarget.Connect`, while the two mismatch tests pass.

- [ ] **Step 3: Implement the minimal common browser fix**

After parsing the invitation, derive and use the effective address:

```kotlin
val payload = invitation.payload
val effectiveLanAddress =
    lanAddress ?: matchingLanAddress(invitation)
val routes = TransportSelector.plan(
    sameLan = effectiveLanAddress != null,
    hostInternetOptIn = payload.internetDirectEnabled,
    guestInternetOptIn = internetOptIn,
    connectAddress = payload.connectAddress,
)
```

Use `effectiveLanAddress` in the `DIRECT_LAN` branch and add:

```kotlin
private fun matchingLanAddress(
    invitation: SignedShareInvite,
): String? {
    val payload = invitation.payload
    return mutableDiscovered.value.firstOrNull {
        val discovered = it.invitation.payload
        discovered.shareId == payload.shareId &&
            discovered.peerId == payload.peerId
    }?.lanAddress
}
```

- [ ] **Step 4: Run focused tests and verify GREEN**

Run:

```sh
./gradlew :share:fabric-common:test \
  --tests com.minekube.connect.share.fabric.FabricShareBrowserTest
```

Expected: all `FabricShareBrowserTest` cases PASS.

- [ ] **Step 5: Run the common-module suite**

Run:

```sh
./gradlew :share:fabric-common:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit the tested fix**

```sh
git add \
  share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/FabricShareBrowser.kt \
  share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/FabricShareBrowserTest.kt
git commit -m "fix(share): prefer discovered LAN route for pasted invites"
```

### Task 2: Build, Install, and Live-Verify Both Fabric Versions

**Files:**
- Verify: `share/fabric-26.2/build/libs/connect-share-fabric-26.2-0.13.3-SNAPSHOT.jar`
- Verify: `share/fabric-1.21.11/build/libs/connect-share-fabric-1.21.11-0.13.3-SNAPSHOT.jar`
- Install: PrismLauncher `26.2`, `26.2 two`, and `1.21.11` instance `mods` directories.

**Interfaces:**
- Consumes: the committed common browser behavior from Task 1.
- Produces: clean Fabric artifacts installed in all configured test instances
  and evidence that a pasted invitation selects the loopback direct proxy.

- [ ] **Step 1: Run repository-wide verification**

Run:

```sh
./gradlew build
```

Expected: `BUILD SUCCESSFUL`, including artifact isolation tests for both
Fabric versions.

- [ ] **Step 2: Install the clean artifacts**

Copy the exact non-dirty JARs into:

```text
/Users/robin/Library/Application Support/PrismLauncher/instances/26.2/minecraft/mods/
/Users/robin/Library/Application Support/PrismLauncher/instances/26.2 two/minecraft/mods/
/Users/robin/Library/Application Support/PrismLauncher/instances/1.21.11/minecraft/mods/
```

Remove only obsolete `connect-share-fabric-*.jar` files from those three
`mods` directories, preserving Fabric API, Fabric Language Kotlin, and all
unrelated mods. Verify each installed artifact's SHA-256 against its matching
build output.

- [ ] **Step 3: Restart both 26.2 test clients and verify mod loading**

Gracefully stop only the two running 26.2 Minecraft processes. Relaunch
PrismLauncher instances `26.2` and `26.2 two`, using the existing offline
`ConnectGuest` profile where configured. Check both `latest.log` files for the
Connect Share mod version and absence of mixin, class-loading, or linkage
errors.

- [ ] **Step 4: Verify a real pasted-invitation direct LAN join**

Start sharing on one 26.2 client, wait until the other client discovers the
same signed share over mDNS, paste the invitation into the join screen, and
join without internet-direct opt-in.

Expected evidence:

- the guest log connects to `127.0.0.1:<ephemeral-port>`, not a
  `*.play.minekube.net` hostname;
- the host accepts the session through `DIRECT_LAN`; and
- the guest reaches the world without a Connect relay connection.
