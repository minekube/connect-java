# Connect Share fallback operations

This is the release contract for the managed Connect fallback used by Connect
Share. It does not assert that production currently meets these targets. A
public rollout may advance only when the named evidence exists for the target
environment and release candidate.

Direct libp2p success is measured separately. A fallback incident must never
disable same-LAN or otherwise working direct joins.

## Service levels

Measure each production region independently over a rolling 30-day window.

| Indicator | Objective | Eligible population |
|---|---:|---|
| Fallback admission availability | 99.9% | Valid, non-revoked attempts reaching a healthy regional edge; excludes host denial, full worlds, expiry, and incompatibility |
| Successful relayed join | 99.0% | Eligible fallback attempts where both clients remain connected through Minecraft login |
| Connection establishment | p95 ≤ 5 s; p99 ≤ 10 s | Time from direct-route exhaustion to a usable fallback tunnel |
| Control decision delivery | p95 ≤ 2 s | Host approval/denial to guest receipt while both control sessions are connected |

The 99.9% monthly availability objective permits about 43 minutes 50 seconds of
unavailability per region. Page on both fast burn (14.4× budget for 5 minutes
and 1 hour) and slow burn (6× for 30 minutes and 6 hours). Pause rollout when
either window fires, successful relayed join drops below 99%, or p99 exceeds 10
seconds for 15 minutes. Roll back when the candidate is correlated with the
regression; otherwise fail over or shed new fallback work while preserving
direct joins.

## Privacy-safe signals

The telemetry boundary is an allowlist. Operational events may contain only:

- coarse timestamp bucket and region;
- client release, Minecraft version, loader, OS family, and CPU family;
- stage enum (`edge_connect`, `admission`, `tunnel`, `minecraft_login`);
- route enum (`direct_lan`, `direct_internet`, `connect_fallback`);
- bounded duration bucket and safe outcome enum;
- retry count bucket, rollout cohort, and aggregate byte bucket.

Never ingest usernames, display names, friend or relationship identifiers,
peer IDs, invitations, endpoint names/tokens, keys, capabilities, IP or socket
addresses, world/server names, chat, contents, complete mod inventories, raw
exceptions, or diagnostic archives. Edge access logs must redact request paths
and authorization before storage. Source addresses required transiently for
transport are not application telemetry and must not be retained beyond the
shortest security/abuse window approved by the threat model.

## Capacity and load gate

Capacity is computed per region from observed, privacy-safe distributions:

`required concurrent tunnels = peak eligible starts/second × p99 session seconds × failover factor`

Reserve at least the larger of 30% headroom or one neighboring region's normal
peak before a public cohort can depend on fallback. Model normal sessions,
long sessions, reconnect storms, creator-driven bursts, maintenance drain, one
region lost, control-plane restart, IPv4/IPv6 imbalance, and an upstream DNS or
certificate degradation. Test control requests and bidirectional relay bytes;
connection-only load is insufficient.

A release evidence bundle records the generator version, anonymized input
histograms, offered/accepted/rejected rates, latency percentiles, resource
saturation, error-budget burn, and estimated cost per successful relay. It
contains no production credentials or per-user traces.

## Abuse controls

- Bind relay authorization to a short-lived, single-share admission; expiry,
  denial, removal, block, stopping the share, and capacity exhaustion revoke it.
- Rate-limit by privacy-reviewed, rotating edge abuse keys rather than social
  identity. Apply separate budgets to endpoint watching, proposals, admission
  decisions, tunnel opens, bytes, and repeated failures.
- Use bounded queues and explicit retry-after responses. Never let abuse
  protection turn into an unbounded client retry loop.
- Protect hosts from unsolicited proposals and guests from replayed approvals.
  Do not inspect Minecraft payload contents or infer a friend graph.
- Escalate suspicious aggregate patterns to a documented review; do not retain
  message contents “just in case.”

Exact limits are deployment configuration, not client constants. They require
load evidence and must be included in the security review.

## Chaos and failover release gate

Before expanding a cohort, prove in staging and then a guarded production
slice:

1. direct success while Connect is unavailable;
2. bounded direct failure followed by one fallback attempt;
3. one regional edge/relay loss and drain to a healthy region;
4. control-plane restart without reused or orphaned admission;
5. expired/revoked credentials, rate limiting, and queue saturation fail safe;
6. recovery after suspend, IP/LAN change, IPv4/IPv6 change, and VPN change;
7. rollback of client and service independently.

Every injected failure has a stop condition, owner, maximum blast radius, and
verified rollback before execution.

## Ownership and runbooks

The Minekube Connect maintainers own the service; each rollout records the
named incident commander and current private on-call route. Public runbooks
must cover regional latency/availability burn, capacity saturation, relay cost
spike, credential abuse, certificate/DNS failure, bad client rollout, and
telemetry privacy incident. Each runbook starts with preserving direct joins,
names a rollback/failover action, defines user communication, and ends with a
postmortem for a material incident.

Broad promotion is blocked until dashboards, alerts, load evidence, failover
evidence, cost budgets, runbooks, and an independent security review are linked
from the release decision. Local tests and a published JAR cannot satisfy this
gate.
