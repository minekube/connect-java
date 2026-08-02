# Connect Share Encrypted Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a player export and restore the persistent Connect Share social identity, relationships, access identity, preferences, and locally managed Connect endpoint as one passphrase-encrypted, integrity-checked, offline backup without revealing plaintext secrets to Minekube.

**Architecture:** Add a loader-neutral recovery archive and transactional store in `share/common`, expose typed recovery operations through `share/fabric-common`, and keep Minecraft file-picker/password rendering in version adapters. The archive uses an authenticated binary envelope with a versioned header, PBKDF2-HMAC-SHA256, AES-256-GCM, a strict filename allowlist, bounded sizes, owner-only permissions where supported, and atomic replace/rollback semantics. Import validates and decrypts the complete bundle before touching live files.

**Tech Stack:** Kotlin/JVM 17+, Arrow `Either`/`Raise`, JCA PBKDF2/AES-GCM/SecureRandom, Gson, JUnit 5, Minecraft Fabric adapters.

## Constraints

- Use only the isolated `codex/connect-share-mod` worktree and PR #94; do not merge.
- Never log, render, or commit archive plaintext, passphrases, private keys, endpoint tokens, friend capabilities, peer IDs, or account IDs.
- Accept passphrases as `CharArray`, clear derived password/key material where JCA permits, and never persist a recovery secret.
- Export only the explicit recovery allowlist; reject traversal, symlinks, oversized files, duplicates, unknown entries, and unsupported versions.
- Keep dashboard endpoint-token import separate from social recovery in names, screens, and docs.
- Import must fail closed and leave the current installation byte-for-byte unchanged on wrong secret, tampering, interruption, or partial-write failure.

### Task 1: Authenticated Recovery Archive

**Files:**
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/recovery/RecoveryArchive.kt`
- Test: `share/common/src/test/kotlin/com/minekube/connect/share/recovery/RecoveryArchiveTest.kt`

- [x] Write failing tests for round trip, wrong secret, one-byte tampering, unsupported version, oversized archive, missing required identity, unknown/duplicate filename, and empty/weak passphrase validation.
- [x] Implement a bounded version-1 envelope with fixed magic, KDF/cipher identifiers, iteration count, random salt/nonce, authenticated header, and AES-GCM ciphertext.
- [x] Encode a versioned JSON manifest containing only filename, byte length, and Base64 content; validate the entire manifest before returning plaintext entries.
- [x] Run `./gradlew :share:common:test --tests '*RecoveryArchiveTest*' --no-parallel` and require the intentional red run followed by green.

### Task 2: Atomic Export, Import, and Rollback

**Files:**
- Create: `share/common/src/main/kotlin/com/minekube/connect/share/recovery/RecoveryStore.kt`
- Test: `share/common/src/test/kotlin/com/minekube/connect/share/recovery/RecoveryStoreTest.kt`

- [x] Write failing tests proving the allowlist, offline round trip, identity rotation rollback, wrong-secret no-op, atomic export replacement, import rollback after an injected replacement failure, recovery from an interrupted transaction, and owner-only output permissions where POSIX is available.
- [x] Export required social identity, gameplay identity, access identity, and friends plus optional preferences and locally stored endpoint config/token; omit absent optional entries and reject missing required entries.
- [x] Stage every import, durably back up existing allowlisted files, write a transaction marker, replace in deterministic order, fsync, mark committed, and clean up; recover a leftover uncommitted marker before any new operation.
- [x] Return an Arrow-typed summary that reveals counts/entry categories but never names, IDs, addresses, or secret material.
- [x] Run `./gradlew :share:common:test --tests '*RecoveryStoreTest*' --no-parallel` and the complete `:share:common:test` suite.

### Task 3: Recovery UX and Relationship Semantics

**Files:**
- Create: `share/fabric-common/src/main/kotlin/com/minekube/connect/share/fabric/recovery/RecoveryViewModel.kt`
- Test: `share/fabric-common/src/test/kotlin/com/minekube/connect/share/fabric/recovery/RecoveryViewModelTest.kt`
- Modify: each supported Fabric settings/friends adapter and `en_us.json`/`de_de.json`
- Modify: `docs/connect-share.md`

- [x] Write failing pure-view-model tests for export confirmation, import preview, wrong-secret/tamper messages, busy-state nonblocking behavior, restart-required success, password clearing, and dashboard-import wording separation.
- [x] Add **Back up friends** and **Restore backup** flows with persistent labels, passphrase confirmation on export, explicit overwrite/restart confirmation on import, clear content/loss warnings, and no secret in mutable state after completion.
- [x] Run file/crypto work on IO dispatchers; render only typed summaries and safe localizable errors.
- [x] Document offline backup, loss of recovery secret, device-copy risks, identity rotation/re-verification, concurrent-device single-active-device semantics, revocation, and the separate dashboard endpoint import.
- [x] Add deterministic tests for simultaneous-device duplicate suppression and rotation invalidating the prior identity, or record the exact remaining protocol gap rather than claiming it.

### Task 4: Evidence and Delivery

**Files:**
- Modify: `docs/connect-share-adoption-evidence.md`
- Modify: `.agents/skills/connect-share-prism-e2e/SKILL.md` and `share/AGENTS.md` only for reusable discoveries

- [x] Build all supported artifacts and assert recovery strings/entrypoints are packaged where applicable.
- [ ] Export from one isolated Prism profile, rotate its local files, import into a stopped second profile, and prove the restored friend identity/relationship offline without exposing archive contents.
- [ ] Verify wrong-secret and tampered archives do not change either profile, then leave both profiles in safe Ask Every Time state with matching intended artifacts.
- [ ] Commit and push incremental reviewed commits to PR #94; comment on #120 with deterministic and product evidence, leaving any account-backed or external-device service work precisely open.
