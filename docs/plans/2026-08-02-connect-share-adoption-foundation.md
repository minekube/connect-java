# Connect Share Adoption Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the friend, compatibility, no-mod, safety, and follow behavior already present in PR #94 into acceptance-level evidence, fix every discovered gap TDD-first, and close only the subissues whose complete criteria are proven.

**Architecture:** Keep the loader-neutral contracts in `share/common`, orchestration and presentation state in `share/fabric-common`, and Minecraft-version rendering/network bridges in their existing adapter modules. Reuse the repository-owned Prism E2E harness for product evidence; add focused regression tests only when an acceptance criterion is not already proved.

**Tech Stack:** Kotlin/JVM 25, Arrow, kotlinx.coroutines, JUnit 5, Fabric/Forge/NeoForge adapters, Gradle, PrismLauncher, libp2p, Minekube Connect.

## Global Constraints

- Work only in `/Users/robin/.treehouse/connect-java-aadf0a/2/connect-java` on `codex/connect-share-mod`.
- Continue in PR #94 and do not merge it.
- Follow `share/AGENTS.md`; use Arrow typed errors/resources and TDD for every behavior change.
- Never print or persist endpoint tokens, invitation capabilities, private keys, IP addresses, or raw identity IDs in evidence.
- Pending relationships receive no presence; Ask Every Time remains the default and final live-test state.
- Friend control traffic remains direct libp2p; Connect is gameplay/no-mod fallback, not a social relay.
- Network work must not block Minecraft's render thread.
- A subissue closes only after every acceptance criterion has code/test or real-client evidence.

---

### Task 1: Acceptance Evidence Matrix

**Files:**
- Create: `docs/connect-share-adoption-evidence.md`
- Read: `share/common/src/main/kotlin/com/minekube/connect/share/**`
- Read: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/**`
- Read: `share/*/src/main/kotlin/com/minekube/connect/share/**`
- Read: `share/*/src/test/kotlin/com/minekube/connect/share/**`

**Interfaces:**
- Consumes: GitHub acceptance criteria from #95, #96, #99, #100, and #103.
- Produces: A criterion-by-criterion table with `Deterministic proof`, `Product proof required`, or `Gap`, exact source/test paths, exact commands, and no unsubstantiated completion claims.

- [x] **Step 1: Map each criterion to source and tests**

Use `rg` to locate the implementation and focused regression for every criterion. Record an exact path and test method; mark missing coverage as `Gap` rather than inferring behavior. A deterministic test does not by itself prove a rendered or real-network product claim.

- [x] **Step 2: Run the deterministic friend/safety suite**

Run:

```bash
./gradlew \
  :share:common:test \
  --tests '*AdmissionControllerTest*' \
  --tests '*CompatibilityProfileTest*' \
  --tests '*FriendControlWireTest*' \
  --tests '*FriendStoreTest*' \
  :share:fabric-common:test \
  --tests '*FabricShareBrowserTest*' \
  --tests '*FriendJoinOrchestratorTest*' \
  --tests '*FriendPresenceMonitorTest*' \
  --tests '*FriendRemovalSyncTest*' \
  --tests '*FriendRequestClientTest*' \
  --tests '*FriendRequestServerTest*' \
  --tests '*FriendsViewModelTest*' \
  --tests '*FollowNextSessionControllerTest*' \
  --tests '*LoadedCompatibilityProfileFactoryTest*' \
  --tests '*SecretRedactionTest*' \
  --tests '*ShareJoinDiagnosticsTest*' \
  --no-parallel
```

Expected: `BUILD SUCCESSFUL`. If a failure is product behavior rather than
environment setup, stop this plan and write a focused TDD fix plan naming the
exact failing production and test paths before changing code.

- [x] **Step 3: Run every packaged adapter contract**

Run:

```bash
./gradlew \
  :share:fabric-1-20-1:test --tests '*Fabric1201ArtifactTest*' \
  :share:fabric-1-21-1:test --tests '*Fabric1211ArtifactTest*' \
  :share:fabric-1-21-11:test --tests '*Fabric12111ArtifactTest*' \
  :share:fabric-26-2:test --tests '*Fabric262ArtifactTest*' \
  :share:forge-1-20-1:test --tests '*Forge1201ArtifactTest*' \
  :share:neoforge-1-21-1:test --tests '*NeoForge1211ArtifactTest*' \
  --no-parallel
```

Expected: `BUILD SUCCESSFUL`, with each artifact test confirming its embedded UX/protocol vocabulary and runtime isolation.

- [x] **Step 4: Write the evidence document**

Create `docs/connect-share-adoption-evidence.md` with one section per subissue and this exact table shape:

```markdown
| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Confirmed friends see privacy-controlled activity | Product proof required | `FriendPresenceMonitorTest` and `FriendsViewModelTest` | Exact-head two-client screenshot/log |
```

Do not mark a real-client criterion proven from a unit test.

- [x] **Step 5: Commit the evidence baseline**

```bash
git add docs/connect-share-adoption-evidence.md docs/plans/2026-08-02-connect-share-adoption-foundation.md
git commit -m "docs(share): map universal party acceptance evidence"
```

---

### Task 2: Exact-Head Product Evidence

**Files:**
- Modify: `docs/connect-share-adoption-evidence.md`
- Modify: `.agents/skills/connect-share-prism-e2e/SKILL.md` only for reusable procedures
- Test: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/PrismFriendJoinE2ETest.kt`

**Interfaces:**
- Consumes: exact unclassified Fabric 26.2 artifact from the current committed head and two isolated Prism profiles.
- Produces: redacted evidence for persistent friend join, compatibility rejection/recovery, no-mod Direct Connect approval/join, relationship safety, and Follow Next Session.

- [ ] **Step 1: Build and hash the exact artifact**

Run:

```bash
./gradlew clean :share:fabric-26-2:connectShareJar --no-parallel
shasum -a 256 share/fabric-26.2/build/libs/connect-share-fabric-26.2-*.jar
```

Select only the unclassified packaged JAR and install exactly one matching copy in each modded Prism profile.

- [ ] **Step 2: Prove confirmed-friend join and compatibility UX**

Use `.agents/skills/connect-share-prism-e2e/SKILL.md`. Require exact artifact hashes, host `joined the game`, guest `Loaded … advancements`, and successful `PrismFriendJoinE2ETest`. Exercise a deliberately mismatched compatibility profile through deterministic tests and inspect the rendered recovery screen without exposing the complete mod inventory.

- [ ] **Step 3: Prove the no-mod Direct Connect path**

Temporarily remove Connect Share only from the guest profile, leaving its Minecraft version compatible. Copy the host's ordinary `*.play.minekube.net` address and launch the guest through vanilla Direct Connect. Exercise approval through the noninteractive admission harness when possible; if Minecraft UI interaction is the only remaining proof, record one explicit human checkpoint instead of automating clicks. Require fresh host/guest login evidence, confirm denial and timeout cannot reuse the admission, then restore the guest artifact and verify its hash afterward.

- [ ] **Step 4: Prove relationship safety and Follow Next Session**

With two confirmed modded friends, enable one-shot follow while the host is unavailable, start a new joinable world, and require exactly one join request. Verify cancellation, active-gameplay non-interruption, removal/block presence revocation, reciprocal removal convergence after reconnect, and final `ASK_EVERY_TIME` state.

- [ ] **Step 5: Record only redacted evidence**

Update the evidence matrix with timestamps, artifact SHA-256, test command/result, and safe log phrases. Never include the friend link, endpoint token, capability, IP, raw peer ID, or account ID.

- [ ] **Step 6: Commit product evidence and reusable wisdom**

```bash
git add docs/connect-share-adoption-evidence.md .agents/skills/connect-share-prism-e2e/SKILL.md share/AGENTS.md
git commit -m "test(share): prove universal party foundation"
```

Omit unchanged paths from `git add`.

---

### Task 3: Close Proven Foundation Subissues

**Files:**
- Modify: GitHub issues #95, #96, #99, #100, and #103
- Modify: PR #94 comment/evidence only; never merge

**Interfaces:**
- Consumes: the complete evidence matrix and pushed exact-head commits.
- Produces: concise issue completion comments and closed subissues only where every criterion is proven.

- [ ] **Step 1: Run the focused and broad local gates**

Run:

```bash
./gradlew :share:common:test :share:fabric-common:test --no-parallel
./gradlew build --no-parallel
git diff --check
```

Expected: both Gradle commands `BUILD SUCCESSFUL`; worktree contains only intentional committed changes.

- [ ] **Step 2: Run no-mistakes and wait for CI**

Run the repository gate with intent naming the exact foundation subissues and product evidence. Accept only review/test/document/lint/push/PR/CI completion with no unresolved correctness finding.

- [ ] **Step 3: Comment and close fully proven issues**

For each eligible issue, comment with the pushed commit, deterministic test selectors, product evidence, and any deliberately deferred non-goal. Close with reason `completed`. Leave any issue with a missing criterion open and add the exact remaining row instead.

- [ ] **Step 4: Update epic and PR evidence**

Comment on #93 with the completed slice and next open dependency. Comment on PR #94 with exact-head evidence, check status, and confirmation that the PR remains unmerged.

- [ ] **Step 5: Begin the next plan**

Create the next independently testable plan for #98 and #97 based on the remaining evidence matrix. Do not mix global operations, device recovery, or growth assets into the foundation commit.
