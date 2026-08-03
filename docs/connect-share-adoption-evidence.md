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
- **Product proof**: a current packaged artifact has passed the relevant real
  client/network evidence gate in addition to deterministic coverage.
- **Gap**: code or focused coverage is incomplete. The issue must remain open.

## Evidence baseline

- Original acceptance-audit commit:
  `6073f2f6101d86d38c71e517148725fd2c089c82`.
- Latest source head with recorded packaged product probes:
  `bd72ea0090a1f4e047ce208d2b73d8fe52b76efd`.
- Deterministic friend/safety command: the focused `:share:common:test` and
  `:share:fabric-common:test` selectors listed in the adoption-foundation plan.
  Result on 2026-08-02: `BUILD SUCCESSFUL`.
- Packaged adapter command: all `*ArtifactTest*` selectors for Fabric 1.20.1,
  1.21.1, 1.21.11, and 26.2; Forge 1.20.1; and NeoForge 1.21.1. Result on
  2026-08-02: 32 tests, zero skipped, zero failures, and zero errors.
- Gap-fix red/green command: focused `FriendControlWireTest`,
  `LoadedCompatibilityProfileFactoryTest`, `ShareScreenPresentationTest`, and
  `ShareUiMessageTest`. The red run failed on the absent wire fingerprint,
  remote fallback messages, and cancellation presentation; the green run
  passed. All four Fabric artifact suites then passed in 1 minute.
- Exact-head direct friend run on 2026-08-02: Fabric 26.2 build, host, and guest
  all used SHA-256
  `c2fbd8708247ee9947cd1404bc39c59d460bc436a08baa8c38d08ff5667076c0`.
  `PrismFriendJoinE2ETest` passed in 51 seconds with fresh host `Bob joined the
  game` and guest `Loaded 2 advancements` evidence. Ask Every Time was restored
  and the host was restarted afterward.
- No-mod product probe after `73f306ff`: the rebuilt host/guest artifact hash is
  `2c9e413d332475eba1d1540120c671db9b9450ebf36218378a7d74b908a0b4b1`.
  A guest with Connect Share removed launched ordinary Direct Connect, and the
  public endpoint resolved and accepted TCP. Both offline and authenticated
  guests remained at Connecting, while the host showed an active Connect watch
  socket, `PersistentConnectState.Available`, and `ShareState.Sharing`, but no
  `PendingAdmission` was created. The Connect edge therefore did not deliver a
  `SessionProposal`; successful vanilla admission and guest-visible denial
  remain external product evidence, not a local completion claim. The guest mod
  was restored with the matching hash.
- Exact-head vanilla product run on 2026-08-03: source head `bd72ea00`, host
  artifact SHA-256
  `27353ba903e93d785204c8163bbfcece09b7b8d503c6809e228bdecfe2a5460b`,
  and a Fabric 26.2 Bob client with zero active Connect Share JARs. Bob launched
  ordinary Minecraft Direct Connect against the host's public Connect address.
  A temporary uncommitted local driver waited for exactly one real pending
  admission and invoked the installed `ShareViewModel`'s normal allow action;
  it carried no identity/request data, added no product bypass, and was removed
  after the run. Alice recorded `Bob joined the game` and Bob recorded a fresh
  advancement load with no Connect Share load or connection-failure marker.
  The exact pre-test `ASK_EVERY_TIME` file was restored byte-for-byte, the guest
  mod was restored at the same artifact digest, and both fresh runtimes were
  verified afterward.
- Production vanilla-denial run on 2026-08-03: Moxy PR #512 was merged and its
  candidate completed the guarded production workflow, including disposable
  Fly E2E, the complete regional rollout, public Java/Bedrock probes, and the
  production Craftless join smoke (`minekube/moxy` workflow
  `30844815477`). An ordinary Fabric 26.2 guest with zero active Connect Share
  JARs then reached the same release-candidate host. With no approval action,
  the guest received the safe host-approval timeout in about 22 seconds; it did
  not enter the world, fall back to Browser Hub, or report generic `Timed out`.
  The guest JAR was restored at its original digest and loaded on a fresh
  runtime. Moxy PR #517 subsequently made the rollout verifier select the last
  surviving candidate per region while retaining exact-image, health, and
  cross-region uniqueness checks.
- Encrypted-recovery deterministic gate on 2026-08-03: complete
  `:share:common:check` and `:share:fabric-common:check` plus all four Fabric
  adapter test tasks passed in 1 minute 31 seconds. Rebuilt exact artifacts
  each contained 31 recovery classes/entrypoints, one English stop-sharing
  safety key, and remained under the 90 MiB artifact gate (approximately
  64.9–65.5 MB). JSON parsing passed for every English and German language
  file. No backup content, path, password, identity, capability, or token was
  emitted during verification.
- Distribution artifact gate on 2026-08-03: all six supported adapter test
  tasks and their tightened 63 MiB size gates passed in 1 minute 10 seconds.
  The exact artifacts were 61,823,460–62,575,797 bytes. Fabric 26.2 additionally
  started two isolated peers from the final packaged JAR and inspected a
  published world. Generic Shadow minimization was rejected after red tests
  exposed missing reflective libp2p dependencies; the retained optimization
  removes only unused Bouncy Castle post-quantum families and keeps all Kotlin,
  networking, conventional cryptography, and cross-platform native support.
- Clean-head direct friend product run on 2026-08-03: source head
  `81ac77b0244db0e6b29abc97559f641f2e935710`, clean Fabric 26.2 artifact,
  host installation, and guest installation all used SHA-256
  `856a7d6694a562cb4e9e45a9db95d610a9783d4002948c2e6b3fbf23c7a821c9`.
  `PrismFriendJoinE2ETest` passed in 40 seconds with fresh host join and guest
  advancement evidence after discovery, authenticated activity, and approval.
  The test-only automatic admission was removed, the host was restarted, and
  `ASK_EVERY_TIME` was verified afterward.

## #95 — one-click presence, request, approval, and join

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Confirmed friends see online, playing, and joinable state on the title screen and in-game | Product proof required | `FriendPresenceMonitorTest` (`refresh projects online state without exposing saved routes`), `FriendsViewModelTest` (`shared singleplayer world exposes request to join when ready`), and `ShareScreenPresentationTest` (`joinable world is the strongest friend state`) | Record both title-screen and in-game rendering from two exact-head clients |
| Pending relationships receive no presence | Deterministic proof | `FriendsViewModelTest` (`outgoing request never exposes presence as a friend`) and `FriendStore.all()` filtering for `CONFIRMED` | None beyond the full regression gate |
| Request to join is one click and never blocks rendering | Product proof required | `FriendJoinOrchestrator`, off-thread coverage in `FriendPresenceMonitorTest` and `ShareViewModelTest`, plus packaged adapter contracts | Record one-click interaction and render responsiveness on an exact packaged client |
| Host receives an actionable notification anywhere in-game | Product proof required | `NewAdmissionTrackerTest` (`only newly pending requests produce notifications`), `SocialEventTrackerTest`, and adapter toast integration | Observe from menu and active gameplay on the packaged client |
| Accepting creates a one-shot admission and connects the guest automatically | Product proof | deterministic one-shot coverage plus the exact-head Prism run's fresh host join and guest advancements evidence | Repeat on the final release candidate |
| Direct libp2p or Connect fallback is selected silently | Product proof required | `TransportSelectorTest` (`failed direct attempts fall back to Connect exactly once`) and `FabricShareBrowserTest` route tests | Record one direct join and one forced fallback without transport-facing UX |
| Re-entering or switching worlds requires no new link | Product proof required | `SharePreferencesStoreTest` (`share with friends remains enabled across restarts until disabled`), `ShareViewModelTest` (`enabled friend sharing resumes automatically in a new world`), and `EndpointIdentityStoreTest` (`one generated identity survives reload and world changes`) | Switch worlds and rejoin using the same confirmed relationship on exact-head clients |
| Every failure gives an understandable next action | Product proof required | typed safe messages in `FriendJoinAttemptFailure`, `ShareUiMessageTest`, and `ShareJoinDiagnosticsTest` | Exercise unavailable, denied, timed-out, incompatible, and transport-failed screens |

## #96 — detect modpack mismatch before joining

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Exchange a privacy-safe compatibility fingerprint before admission | Deterministic proof | `FriendControlWireTest` (`compatibility fingerprint is carried and validated on the wire`) rejects a tampered fingerprint; `FriendJoinOrchestratorTest` proves compatibility runs before approval | None beyond the full regression gate |
| Distinguish Minecraft, loader, missing-mod, and mod-version mismatch | Deterministic proof | `CompatibilityProfileTest` (`minecraft loader missing mod and version differences are distinct`) | None beyond the full regression gate |
| Never report a modpack mismatch as direct or Connect failure | Deterministic proof | `FriendJoinOrchestrator` returns `FriendJoinAttemptFailure.Compatibility` before approval; covered by both mismatch tests in `FriendJoinOrchestratorTest` | None beyond the full regression gate |
| Show a concise list of blocking differences | Product proof required | semantic rows in `ShareScreenPresentation.compatibilityLines`; `ShareScreenPresentationTest` (`compatibility details use localizable semantic lines`) | Inspect the exact packaged compatibility screen |
| Copy or link matching Modrinth or CurseForge pack metadata | Product proof required | `LoadedCompatibilityProfileFactoryTest` covers Modrinth, CurseForge, and rejection of HTTP, credential-bearing, and file URLs; all Fabric mismatch screens copy the safe pack URL | Prove the rendered copy action on an exact packaged client |
| Advanced override supports compatible client-only differences | Product proof required | client-only mods are omitted in `LoadedCompatibilityProfileFactoryTest`; required-mod mismatch uses explicit `allowModMismatch` in `FriendJoinOrchestratorTest` | Exercise the exact packaged Try Anyway flow |
| Never upload a complete mod inventory without explicit consent | Deterministic proof | `LoadedCompatibilityProfileFactoryTest` proves only universal/server gameplay mods enter the peer-to-peer profile; `docs/connect-share.md` states the exchange is not uploaded | None beyond the full regression gate |

## #97 — broad versions, loaders, and one-click distribution

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Maintain latest plus the 1.21.1 and 1.20.1 modpack anchors | Deterministic proof | Fabric adapters cover 26.2, 1.21.11, 1.21.1, and 1.20.1; the six-adapter gate passed from the current head | Define and operate the measured latest-version release target after the first public release |
| Provide Fabric, Forge, and NeoForge adapters | Deterministic proof | Fabric 1.20.1/1.21.1/1.21.11/26.2, Forge 1.20.1, and NeoForge 1.21.1 all built and passed packaged artifact tests | Real-client startup and join evidence remains required for every release target |
| Publish verified artifacts on Modrinth, CurseForge, and GitHub Releases | Gap | Artifacts have unambiguous loader/version archive names; `.github/workflows/connect-share-release.yml` fails closed, publishes the six artifacts, creates checksums and GitHub/Sigstore provenance, and verifies release assets/attestations | Marketplace projects, credentials, public metadata, a disposable prerelease proof, and final publication are external release operations and have not occurred from this unmerged PR |
| Modrinth App and Prism install dependencies automatically | Product proof required | Fabric metadata declares Fabric API and Fabric Language Kotlin dependencies; Forge/NeoForge package KotlinForForge in their distributable artifact | Prove fresh one-click installs through published Modrinth metadata and Prism on all supported loader families |
| Permit modpack inclusion and document dependencies/compatibility | Deterministic proof | `docs/connect-share.md` explicitly permits public/private modpack inclusion under MIT, names every loader/version artifact, and documents automatic and manual Kotlin/loader dependencies | Marketplace copy must reproduce the same contract before publication |
| CI builds every adapter and proves packaged startup | Deterministic proof | CI adapter tasks exist; all six adapter suites passed locally. Fabric 26.2's exact packaged JAR now starts two isolated libp2p peers and inspects a published world | Extend exact packaged peer startup to the release matrix and retain real Minecraft startup/join gates |
| Track and safely reduce artifact size | Deterministic proof | Every adapter now has a 63 MiB build gate; current exact artifacts are 61,823,460–62,575,797 bytes. The shared payload removes only unused Bouncy Castle PQC families, and a real packaged-peer test guards reflective runtime behavior | Continue measuring published download size; do not use generic static minimization on jvm-libp2p |

## #98 — reliable joining and actionable recovery

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Direct first and exactly one automatic Connect fallback | Product proof | `TransportSelectorTest` covers the ordering and exactly-once contract; `PrismFriendJoinE2ETest` then authenticated a confirmed friend, closed the live guest direct node after approval while retaining the discovered LAN route, asserted the Connect target, and completed a fresh Fabric 26.2 host/guest login | Repeat on the final release artifact and remaining loader clients |
| UI/control work never blocks rendering | Deterministic proof | `FriendPresenceMonitorTest`, `FriendsViewModelTest`, `ShareViewModelTest`, and `RecoveryViewModelTest` use injected IO dispatchers and test off-thread work/cancellation | Profile the final packaged UI during representative slow/unreachable paths on each supported runtime |
| Requests, cancellation, removal, shutdown, and retry are bounded | Deterministic proof | `FriendRequestClientTest` proves prompt cancellation and acknowledged removal; `AdmissionControllerTest` proves expiry/cancellation/capacity; `ShareCoordinatorTest` proves idempotent exhaustive shutdown; direct/control/login deadlines are explicit | Real suspend/resume and process/network-loss product evidence across OSes |
| Reconnect after IP, LAN, or world changes needs no relinking | Deterministic proof | `FriendStoreTest` persists signed candidates/consent; discovery retains per-peer refreshed routes; `ShareCoordinatorTest` and identity-store tests preserve identity through world replacement | Two-machine IP/LAN/VPN change and world-switch evidence on exact release artifacts |
| Concise stage, actionable failure, and secret-safe diagnostics | Product proof required | `ShareUiMessageTest`, `ShareJoinDiagnosticsTest`, `SecretRedactionTest`, and the plain-language stage/failure models reject transport jargon and secret values | Exercise unavailable, denied, timeout, incompatible, fallback-failed, and resumed states in packaged clients |
| Automated two-client direct, fallback, offline, online, and network-change cases | Partial product proof | Real libp2p direct, forced Connect fallback, and vanilla no-mod Connect joins pass on Fabric 26.2; deterministic selector/auth/network refresh cases pass | Paid online-auth, two-machine network-change, and remaining loader automation still require the external matrix |
| Real-client startup/join gate for every supported release target | Gap | Six packaged adapter suites pass and Fabric 26.2 clean-head direct join is proven | Fabric 1.20.1/1.21.1/1.21.11 plus Forge 1.20.1 and NeoForge 1.21.1 startup/join, then OS/architecture breadth |

## #99 — let friends join without installing the mod

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Host copies a short ordinary Minecraft server address | Product proof required | `docs/connect-share.md` and adapter vocabulary cover the action; an exact-head no-mod client completed a real vanilla join through the ordinary public address after the normal host admission action | Inspect the packaged copy action; repeat the successful vanilla join on the final release candidate |
| Stable Connect endpoint token is reused across worlds | Product proof required | `EndpointIdentityStoreTest` (`one generated identity survives reload and world changes`) and `PersistentConnectIngressTest` (`title startup and world leases share one connector until shutdown`) | Record the same redacted endpoint identity fingerprint across two worlds |
| World changes do not create endpoint database spam | Product proof required | the persistent ingress and identity tests above make no create call on world replacement | Verify through a two-world packaged session and, where available, redacted endpoint-count telemetry |
| Address reveals no local or public IP in the UI | Product proof required | `SecretRedactionTest`, `ShareJoinDiagnosticsTest`, and the ordinary Connect hostname presentation | Inspect copy/status UI and diagnostics on the exact artifact |
| Host approval and capacity still apply | Product proof required | `AdmissionControllerTest` covers timeout, capacity, one-shot approval, and identity binding; the exact-head vanilla run proved one real pending admission, the normal allow action, and completed gameplay; the earlier live probe proved bounded timeout/denial | Record packaged capacity exhaustion and repeat approval/denial on the final release candidate |
| Confirmed modded friends retain richer presence and direct-first joining | Product proof required | presence tests plus `TransportSelectorTest` (`same LAN is attempted before internet and Connect`) | Record a modded friend join after restoring the exact artifact |
| Errors distinguish unavailable host from invalid or expired admission | Product proof | `RemoteLoginMessage` and `FabricSessionAdmissionGateTest` provide distinct text and reserve time before vanilla's timeout; Moxy PR #512 is deployed, and a production no-mod run rendered its safe localized host-approval timeout in about 22 seconds without Browser Hub fallback or generic timeout | Repeat unavailable, capacity, explicit decline, and timeout cases across the remaining release adapters |

## #100 — privacy, permissions, and relationship safety

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Only confirmed friends receive presence or joinable activity | Deterministic proof | `FriendStore.all()` exposes only confirmed relationships; `FriendsViewModelTest` rejects presence for outgoing requests and raw status | None beyond the full regression gate |
| Display name is never identity | Deterministic proof | `SavedFriend` keys relationships by authenticated peer identity; `AdmissionControllerTest` requires a new approval for a copied offline name, bounds/expires one-shot grants, and `ApprovedJoinTrackerTest` requires the signature-verified invitation peer before automatic friendship | None beyond the full regression gate |
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
| TDD covers expiry, cancellation, reconnect, removal, blocks, duplicates, and simultaneous follow | Deterministic proof | `FollowNextSessionControllerTest` explicitly covers every listed case; `ShareScreenPresentationTest` fixes visible automatic-cancellation copy; every Fabric adapter renders it | Verify the packaged cancellation notification during product proof |

## #117 — one-click HTTPS invite/install/resume handoff

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Every invite has a safe HTTPS form led by the human join action | Gap | `docs/connect-share-handoff.md` defines the social copy and fragment-only secret boundary | A real reviewed/deployed handoff page does not exist in this repository; the client deliberately does not emit a dead link |
| Resolve version, loader, OS, launcher, dependencies, and vanilla path without disclosure | Deterministic design | The handoff contract requires a secret-free artifact manifest, allowlisted launcher adapters, required dependencies, and locally verified Connect-hostname fallback | Implement the web application and launcher adapters against published marketplace projects |
| Resume the original invitation exactly once after install/restart | Deterministic design | The contract defines digest-bound expiring state, owner-only local transfer, atomic consume/delete, acknowledgement, and explicit retry | Implement and TDD the signed resume protocol in both web/launcher boundary and mod after the handoff owner/repository is selected |
| Safe expired, revoked, incompatible, malicious, declined, cancelled, and retry states | Deterministic design | Explicit resolution flow and E2E matrix in the handoff contract | Browser/launcher implementation and cross-OS E2E are external/missing |
| Preview and measurement reveal no secrets or graph | Deterministic design | Fragment never reaches HTTP; CSP/referrer/storage/analytics rules and aggregate opt-in boundary are explicit | Independent web privacy review plus log/referrer evidence on the deployed origin |

## #118 — staged launch, measurement, modpacks, and creators

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Consistent marketplace promise and sub-30-second demonstration | Gap | `docs/connect-share-launch.md` fixes the promise and exact demonstration story | Marketplace pages, visual assets, video, and publication are external launch work |
| Plain-language modpack, dependency, privacy, security, support, and compatibility material | Deterministic proof | `docs/connect-share.md`, launch contract, threat model, testing guide, and MIT redistribution section cover the source material | Final marketplace/creator copy review and published URLs |
| Creator/modpack kit and staged diverse beta | Gap | Launch contract enumerates approved assets, copy lengths, metadata, checksums, forecast/support form, cohorts, and gates | Produce assets, recruit cohorts, staff support, forecast capacity, and run the beta |
| Localization covers the largest reachable populations | Gap | English/German locale parity is packaged; the launch contract defines the next locale order and safety-copy release gate | Translate, review, and package Brazilian Portuguese, Spanish, French, Russian, Simplified Chinese, Japanese, and evidence-driven additions |
| Privacy-preserving opt-in success/reliability/retention metrics | Deterministic design | Launch contract defines default-off local aggregation, allowed measures, suppression, and a strict forbidden-field list | Reviewed endpoint, consent UI, retention/deletion policy, privacy review, and staged data-quality proof; no telemetry is silently enabled |
| Launch/pause/rollback/graduation criteria precede promotion | Deterministic proof | Four guarded stages, exact graduation/pause conditions, required evidence bundle, and independent rollback are documented | Execute the gates with real product and service data before each stage |

## #119 — global Connect fallback operations and security review

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Define regional availability, establishment, and successful-relay SLOs | Deterministic proof | `docs/connect-share-operations.md` defines 99.9% admission availability, 99% eligible relayed join, p95/p99 latency, error budget, and multi-window burn alerts | Instrument and prove the indicators in each production region |
| Load test and capacity-plan realistic sessions, bursts, failover, and degraded upstreams | Deterministic design | Capacity formula, headroom rule, required distributions, evidence bundle, and scenarios are specified | Service repository load generator, staging/production-safe execution, dashboards, and signed results are external/missing |
| Rate limits and abuse controls protect every boundary without content/graph collection | Deterministic design | Admission-scoped authorization, rotating abuse keys, separate budgets, bounded queues, retry-after, and forbidden inspection are specified | Deployment configuration, load tuning, privacy review, and abuse simulation |
| Threat-model all critical assets and obtain independent review | Product proof required | `docs/connect-share-threat-model.md` covers invites, identity, endpoint import, admission, recovery, relay, diagnostics/metrics, HTTPS, and updates with required controls | Independent reviewer, findings/remediation, deployment diagrams, and sign-off are external/missing |
| Privacy-safe observability, alerting, ownership, runbooks, incidents, and postmortems | Deterministic design | Allowlisted signal schema, redaction/retention boundary, alert windows, ownership and required runbooks/communications are explicit | Dashboards, private on-call route, runbook links, exercises, and production evidence |
| Cost budgets, chaos/failover, staged rollout, and rollback | Deterministic design | Cost/session evidence and seven chaos gates preserve direct joins and require bounded blast radius/rollback | Regional service deployment, cost data, failure injection, and executed evidence |
| Signed and verifiable release artifacts | Product proof required | Release workflow now uses `actions/attest@v4`, uploads checksums, and verifies GitHub attestations; workflow syntax passes `actionlint` | Run against a disposable published prerelease and verify every public marketplace digest against the attested files |

## #120 — encrypted identity and friend recovery

| Acceptance criterion | Status | Evidence | Remaining proof |
|---|---|---|---|
| Offline export/import keeps recovery plaintext away from Minekube | Deterministic proof | `RecoveryArchiveTest` covers AES-256-GCM round trip, random salt/nonce, PBKDF2-HMAC-SHA256, strict allowlisting, bounds, and redacted values; `RecoveryStoreTest` proves complete offline transfer | Inspect and exercise the exact packaged file-picker flow without recording its path or contents |
| Wrong password, tampering, unsupported versions, and partial writes fail closed | Deterministic proof | `RecoveryArchiveTest` makes wrong passwords and one-byte tampering the same authentication failure; `RecoveryStoreTest` proves wrong-secret no-op, injected rollback, and next-start recovery after simulated process loss | Repeat wrong-password and damaged-file cases with disposable packaged profiles |
| Export and restored files are owner-only and atomically replaced | Deterministic proof | `RecoveryStoreTest` verifies POSIX `0600`, atomic replacement of an existing backup, deterministic staged import, and rollback | Confirm permissions on the final packaged-client backup where POSIX applies |
| Recovery UI is nonblocking, explicit, safe, localized, and distinct from dashboard token import | Product proof required | `RecoveryViewModelTest` covers off-thread work, matching export secrets, authenticated preview, explicit restore confirmation, restart copy, active-share refusal, and password-buffer clearing; all four Fabric adapters compile with English and German recovery strings | Inspect the final screen at minimum and narrow window sizes; verify native save/open dialogs manually |
| Device loss, rotation, revocation, and concurrent restored-copy semantics are honest | Gap | `docs/connect-share.md` defines the offline archive as a single-device transfer and identifies re-verification/removal/blocking; it explicitly warns that copied profiles must not run simultaneously | A future signed identity-rotation protocol is required to revoke a lost active device and deterministically suppress two restored copies without trusting a central social relay |
| Optional account-backed recovery is visible, revocable, and rate limited | Gap | No plaintext or recovery secret is uploaded by the local implementation | Requires an authenticated Minekube recovery service, threat model, enrollment/revocation API, audit trail, and abuse/rate-limit controls; it cannot be truthfully completed inside this client-only PR |

## Open foundation gaps

The baseline intentionally leaves #95, #96, #99, #100, and #103 open until the
remaining exact-head product claims are observed. The first audit fixes remain,
the direct, forced Connect-fallback, and vanilla no-mod Prism joins are proven,
and the latest review also bound automatic friendship to the signed direct peer
while making one-shot preapprovals expiring and bounded. The no-mod run proves
Connect session delivery, host admission, and completed gameplay through the
ordinary public address. Moxy PR #512 is now deployed, its guarded production
workflow is green, and an unmodified guest received the intended actionable
terminal denial. Moxy PR #517 also prevents repeated replacement history from
making the final regional-candidate verifier demand a superseded machine.
