# Connect Share Adoption Evidence

This document tracks acceptance evidence for the first universal-party slice of
[epic #93](https://github.com/minekube/connect-java/issues/93) in
[PR #94](https://github.com/minekube/connect-java/pull/94). It intentionally
distinguishes deterministic proof from product proof on an exact packaged
artifact. No endpoint token, invitation capability, private key, address, raw
peer ID, or account ID belongs in this document.

Status meanings:

- **Deterministic proof**: the criterion is implemented and covered by a
  focused automated test, but any rendered or real-network claim still needs
  exact-head product evidence.
- **Product proof required**: useful implementation and automated coverage
  exist, but the acceptance claim depends on a packaged-client or real-network
  observation that has not yet been recorded for the current commit.
- **Gap**: code or focused coverage is incomplete. The issue must remain open.

## Evidence baseline

- Commit under test: `6073f2f6101d86d38c71e517148725fd2c089c82`.
- Deterministic friend/safety command: the focused `:share:common:test` and
  `:share:fabric-common:test` selectors listed in the adoption-foundation plan.
  Result on 2026-08-02: `BUILD SUCCESSFUL`.
- Packaged adapter command: all `*ArtifactTest*` selectors for Fabric 1.20.1,
  1.21.1, 1.21.11, and 26.2; Forge 1.20.1; and NeoForge 1.21.1. Result on
  2026-08-02: 32 tests, zero skipped, zero failures, and zero errors.

## #95 — one-click presence, request, approval, and join

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Confirmed friends see online, playing, and joinable state on the title screen and in-game | Product proof required | `FriendPresenceMonitorTest` (`refresh projects online state without exposing saved routes`), `FriendsViewModelTest` (`shared singleplayer world exposes request to join when ready`), and `ShareScreenPresentationTest` (`joinable world is the strongest friend state`) | Record both title-screen and in-game rendering from two exact-head clients |
| Pending relationships receive no presence | Deterministic proof | `FriendsViewModelTest` (`outgoing request never exposes presence as a friend`) and `FriendStore.all()` filtering for `CONFIRMED` | None beyond the full regression gate |
| Request to join is one click and never blocks rendering | Product proof required | `FriendJoinOrchestrator`, off-thread coverage in `FriendPresenceMonitorTest` and `ShareViewModelTest`, plus packaged adapter contracts | Record one-click interaction and render responsiveness on an exact packaged client |
| Host receives an actionable notification anywhere in-game | Product proof required | `NewAdmissionTrackerTest` (`only newly pending requests produce notifications`), `SocialEventTrackerTest`, and adapter toast integration | Observe from menu and active gameplay on the packaged client |
| Accepting creates a one-shot admission and connects the guest automatically | Product proof required | `AdmissionControllerTest` (`approved friend request authorizes exactly one following gameplay join`) and `FriendJoinOrchestratorTest` (`shared world opens gameplay only after approval`) | Record fresh two-client host/guest login evidence on the exact artifact |
| Direct libp2p or Connect fallback is selected silently | Product proof required | `TransportSelectorTest` (`failed direct attempts fall back to Connect exactly once`) and `FabricShareBrowserTest` route tests | Record one direct join and one forced fallback without transport-facing UX |
| Re-entering or switching worlds requires no new link | Product proof required | `SharePreferencesStoreTest` (`share with friends remains enabled across restarts until disabled`), `ShareViewModelTest` (`enabled friend sharing resumes automatically in a new world`), and `EndpointIdentityStoreTest` (`one generated identity survives reload and world changes`) | Switch worlds and rejoin using the same confirmed relationship on exact-head clients |
| Every failure gives an understandable next action | Product proof required | typed safe messages in `FriendJoinAttemptFailure`, `ShareUiMessageTest`, and `ShareJoinDiagnosticsTest` | Exercise unavailable, denied, timed-out, incompatible, and transport-failed screens |

## #96 — detect modpack mismatch before joining

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Exchange a privacy-safe compatibility fingerprint before admission | Gap | `CompatibilityProfile.fingerprint()`, filtered profile transport in `FriendControlWire`, and compatibility-before-approval ordering in `FriendJoinOrchestratorTest` | Add a focused wire-level assertion that the fingerprint is carried and validated before admission |
| Distinguish Minecraft, loader, missing-mod, and mod-version mismatch | Deterministic proof | `CompatibilityProfileTest` (`minecraft loader missing mod and version differences are distinct`) | None beyond the full regression gate |
| Never report a modpack mismatch as direct or Connect failure | Deterministic proof | `FriendJoinOrchestrator` returns `FriendJoinAttemptFailure.Compatibility` before approval; covered by both mismatch tests in `FriendJoinOrchestratorTest` | None beyond the full regression gate |
| Show a concise list of blocking differences | Product proof required | semantic rows in `ShareScreenPresentation.compatibilityLines`; `ShareScreenPresentationTest` (`compatibility details use localizable semantic lines`) | Inspect the exact packaged recovery screen |
| Copy or link matching Modrinth or CurseForge pack metadata | Gap | `LoadedCompatibilityProfileFactory` accepts safe HTTPS metadata and recognizes both platforms; only Modrinth has focused coverage | Add CurseForge and unsafe-link coverage, then prove the rendered copy/open action |
| Advanced override supports compatible client-only differences | Product proof required | client-only mods are omitted in `LoadedCompatibilityProfileFactoryTest`; required-mod mismatch uses explicit `allowModMismatch` in `FriendJoinOrchestratorTest` | Exercise the exact packaged Try Anyway flow |
| Never upload a complete mod inventory without explicit consent | Deterministic proof | `LoadedCompatibilityProfileFactoryTest` proves only universal/server gameplay mods enter the peer-to-peer profile; `docs/connect-share.md` states the exchange is not uploaded | None beyond the full regression gate |

## #99 — let friends join without installing the mod

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Host copies a short ordinary Minecraft server address | Product proof required | `docs/connect-share.md` documents **Copy server address** and adapter artifact vocabulary asserts the friends-first UI | Copy it on the exact host artifact and join from a profile without Connect Share |
| Stable Connect endpoint token is reused across worlds | Product proof required | `EndpointIdentityStoreTest` (`one generated identity survives reload and world changes`) and `PersistentConnectIngressTest` (`title startup and world leases share one connector until shutdown`) | Record the same redacted endpoint identity fingerprint across two worlds |
| World changes do not create endpoint database spam | Product proof required | the persistent ingress and identity tests above make no create call on world replacement | Verify through a two-world packaged session and, where available, redacted endpoint-count telemetry |
| Address reveals no local or public IP in the UI | Product proof required | `SecretRedactionTest`, `ShareJoinDiagnosticsTest`, and the ordinary Connect hostname presentation | Inspect copy/status UI and diagnostics on the exact artifact |
| Host approval and capacity still apply | Product proof required | `AdmissionControllerTest` covers timeout, capacity, one-shot approval, and identity binding; `ShareCoordinatorTest` validates the guest range | Record approval, denial/timeout, and capacity behavior for a vanilla guest without automating Minecraft clicks |
| Confirmed modded friends retain richer presence and direct-first joining | Product proof required | presence tests plus `TransportSelectorTest` (`same LAN is attempted before internet and Connect`) | Record a modded friend join after restoring the exact artifact |
| Errors distinguish unavailable host from invalid or expired admission | Gap | invitation expiry and host-denial translation keys exist in `ShareUiMessageTest`; no focused no-mod assertion covers the complete distinction | Add no-mod admission outcome coverage and inspect the vanilla disconnect copy |

## #100 — privacy, permissions, and relationship safety

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Only confirmed friends receive presence or joinable activity | Deterministic proof | `FriendStore.all()` exposes only confirmed relationships; `FriendsViewModelTest` rejects presence for outgoing requests and raw status | None beyond the full regression gate |
| Display name is never identity | Deterministic proof | `SavedFriend` keys relationships by authenticated peer identity; `AdmissionControllerTest` (`offline reconnect with copied name requires a new approval`) | None beyond the full regression gate |
| Requests, reciprocal requests, removals, and blocks converge | Product proof required | `FriendRequestServerTest` covers crossed requests and authenticated idempotent removal; `FriendRemovalSyncTest` covers later acknowledgement; `FriendStoreTest` covers durable blocks | Record reciprocal request, offline removal/reconnect, and block behavior with two clients |
| Per-friend Ask Every Time, Auto-Accept, and Never Allow policies | Product proof required | `FriendStoreTest` (`never allow is durable and distinct from ask every time`) and `FriendRequestServerTest` (`never allow declines join without notifying the host`) | Inspect all three settings and validate exact packaged behavior |
| Online, playing, current-server/world, and joinable state can be hidden independently | Product proof required | `SharePreferencesStoreTest` and the privacy cases in `FriendRequestServerTest`/`FriendsViewModelTest` | Exercise each toggle from the packaged privacy UI |
| Invites and diagnostics reveal no token, key, or local/public IP | Product proof required | `ShareInviteCodecTest` (`signed invitation round trips without leaking its capability`), `SecretRedactionTest`, and `ShareJoinDiagnosticsTest` | Inspect copied diagnostics and all social screens on the exact artifact |
| Removal or block revokes later admission and presence | Product proof required | `AdmissionControllerTest` removal-revocation cases, `ApprovedJoinTrackerTest`, and `FriendStoreTest` block behavior | Record revocation after reconnect with two clients |
| Security and privacy behavior is documented plainly | Deterministic proof | the **Privacy and safety** section of `docs/connect-share.md` | Product-copy review before release |

## #103 — follow a friend into the next joinable world

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Follow survives title-screen and menu transitions | Product proof required | `FollowNextSessionController` is installation-scoped through `FriendsViewModel`; packaged adapters poll it from title/menu and gameplay | Record navigation through title/menu before the host becomes joinable |
| At most one request is emitted for a world-presence epoch | Deterministic proof | `FollowNextSessionControllerTest` (`joinable epoch emits one request and duplicate presence cannot storm`) | None beyond the full regression gate |
| Repeated presence cannot create request storms | Deterministic proof | duplicate-epoch test above and `reconnect with a new world epoch can retry without duplicating either epoch` | None beyond the full regression gate |
| Auto-accept requires explicit per-friend policy | Deterministic proof | `FriendPermissions.canJoinAutomatically` requires `AUTO_ACCEPT`; request-server policy tests cover Ask/Never Allow | None beyond the full regression gate |
| Active gameplay is never interrupted automatically | Product proof required | `FollowNextSessionControllerTest` (`active gameplay is never interrupted and receives one join offer`) | Observe Join Now rather than forced connection during active gameplay |
| Both players receive understandable notifications | Product proof required | follower toasts in each Fabric adapter, normal host admission notifications, and `SocialEventTrackerTest` | Observe both sides on exact packaged clients |
| TDD covers expiry, cancellation, reconnect, removal, blocks, duplicates, and simultaneous follow | Gap | `FollowNextSessionControllerTest` covers expiry, cancellation, reconnect, removal through confirmed-set loss, duplicates, and simultaneous follow | Add an explicit blocked-relationship regression and verify the packaged cancel notification |

## Open foundation gaps

The baseline intentionally leaves #95, #96, #99, #100, and #103 open. The
next TDD slice starts with the three explicit automated gaps above, then uses
the exact-head Prism harness for product proof. Minecraft UI clicks are never
automated; any irreducible approval interaction is recorded as a human
checkpoint with all other evidence gathered noninteractively.
