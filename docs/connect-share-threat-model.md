# Connect Share threat model

This model covers the friend/social plane, direct gameplay, Connect fallback,
recovery, diagnostics, and update distribution. It is a living engineering
artifact, not an independent security review.

## Assets and trust boundaries

Protected assets are the persistent social private key, ephemeral share key,
Connect endpoint token, relationship graph, approval decisions, presence,
private network addresses, recovery archive/password, Minecraft account
authentication, and release artifacts.

Trust boundaries exist between two players, the local Minecraft process and
launcher/filesystem, direct libp2p peers, Minekube Connect edge/control/relay,
the dashboard credential export, the future HTTPS handoff page, marketplace
publishers, GitHub Actions, and recovery storage selected by the user.

Display names are untrusted labels. Authorization uses authenticated peer or
Minecraft identity plus a scoped, expiring admission.

## Threats and required controls

| Boundary | Threat | Required control and evidence |
|---|---|---|
| Friend invite | Forgery, tampering, replay, capability disclosure, malicious routes | Signed bounded invitation, authenticated peer binding, expiry, route validation, redacted values, no relay addresses; codec and tamper tests |
| Relationship | Name impersonation, pending-presence leak, crossed requests, removal/block divergence | Identity-keyed records, no pending presence, idempotent reciprocal confirmation, durable revocation and convergence tests |
| Admission | Replay, approval theft, unsolicited join, capacity bypass, online-to-offline downgrade | One-shot share/connection binding, bounded deadline, capacity gate, explicit auth mode, stop/removal/block revocation tests |
| Direct network | Private/public address disclosure, SSRF-like route injection, unbounded dialing | Explicit disclosure/guest opt-in, signed candidates, protocol/address allowlist, no circuit relay, bounded route attempts, secret-safe diagnostics |
| Connect credential | Token theft, confused endpoint, unsafe import, log leakage | Owner-only files, config/token pairing, authenticated import, stable reuse, environment-managed immutability, redaction and rollback tests |
| Connect relay | Unauthorized bandwidth, amplification, host/guest abuse, regional compromise | Short-lived admission authorization, independent rate/byte limits, bounded queues, regional isolation, encrypted transport, load/chaos evidence |
| Recovery | Offline guessing, tampering, partial replace, copied identity concurrency, lost-device compromise | PBKDF2-HMAC-SHA256 at 600,000 iterations, AES-256-GCM, random salt/nonce, fixed allowlist, 0600, authenticated preview, atomic rollback; rotation remains unresolved |
| Diagnostics/metrics | Secret or social-graph exfiltration, raw exception leakage, re-identification | Explicit local copy/opt-in, strict schemas, bounded enums/buckets, no stable social identifier, retention review, redaction tests |
| HTTPS handoff | Invite leakage through server logs/referrers/analytics, hostile install link, repeated resume | Fragment-only secret, local signature validation, restrictive CSP/referrer policy, allowlisted launcher adapters, one-shot state, expiry/revocation E2E |
| Updates | Compromised publisher/CI, artifact substitution, dependency confusion, rollback attack | Protected tag/release, least-privilege publish job, checksums and provenance attestation, marketplace digest verification, staged rollout and rollback |

## Recovery and device caveat

The offline archive safely transfers one identity; it does not revoke a lost
still-active device or provide conflict-free concurrent devices. Until a signed
rotation/re-verification protocol exists, a lost device requires removing or
blocking the old relationship and linking a new identity. An account-backed
recovery service additionally needs enrollment authentication, revocation,
rate limits, audit, and a server-blind encryption design.

## Review gate

Before broad promotion, an independent reviewer must receive this model,
protocol formats, cryptographic choices, release workflow, recovery tests,
admission tests, relay authorization design, operational data schema, and
deployment diagrams. Findings have owners, severity, target release, and a
public-safe remediation record. Critical/high findings block launch; accepted
risk requires a named maintainer, expiry date, and compensating control.
