# Connect Share staged launch

**Promise:** Install once. See your friends. Join whatever they are playing. No
server setup.

Growth is gated by successful shared play, not download count. Marketplace or
creator promotion may not outrun fallback capacity, security review, support,
or the exact packaged-client evidence matrix.

## Rollout stages

| Stage | Cohort | Graduate when | Pause or roll back when |
|---|---|---|---|
| 0 - internal | Maintainers and disposable test pairs | Direct, fallback, no-mod, recovery, compatibility, and all adapter startup gates pass | Any secret leak, unbounded hang, corrupt recovery, or reproducible join regression |
| 1 - closed beta | Diverse invited pairs across regions, offline/online profiles, vanilla-like and major modpacks | ≥95% eligible invite-to-join, p95 request-to-world <10 s, ≥99% crash-free Share sessions, support response <1 business day | Error-budget alert, security finding, generic/unactionable failures >2%, or support backlog >2 business days |
| 2 - marketplace beta | Guarded percentage of published installs | Two weeks within regional fallback SLOs, successful repeat sessions, verified rollback, no unresolved high-severity issue | SLO burn, cost budget breach, launcher dependency failure, or regression concentrated in a version/loader |
| 3 - creator/modpack pilot | Small approved packs and creators with forecast traffic | Capacity headroom survives forecast burst and each cohort has an owner/support channel | Forecast exceeds reserved capacity, abuse spike, or cohort join success misses beta baseline |
| 4 - broad release | Supported marketplaces and packs | Ongoing SLO/error-budget and retention review | Same automated pause gates; rollback client/service independently |

Each release decision links the exact commit/artifact digests, adapter matrix,
two-client evidence, no-mod result, fallback load/chaos results, current known
issues, privacy review, security review, dashboard, cost budget, rollback, and
incident owner.

## Opt-in measurement contract

Metrics are off by default until a reviewed endpoint and consent UI exist.
Consent must be understandable, reversible, and independent of gameplay. The
client aggregates locally and uploads only counts and coarse duration buckets:

- invite received → already installed / newly installed / vanilla path;
- request → approved / denied / expired / cancelled;
- join stage and safe outcome;
- route class and duration bucket;
- actionable recovery chosen and whether a later attempt succeeded;
- number of locally recognized repeat friend-pair sessions as an aggregate
  count, never the peer or relationship key;
- crash-free Share session count and install-source enum.

No event contains a persistent player/install/social identifier, friend graph,
invitation payload, endpoint credential/name, peer key/ID, IP/address, username,
world/server name, chat, contents, complete inventory, or raw stack trace.
Small cohorts and rare dimension combinations are suppressed. Retention and
deletion windows are documented before collection. Product operation must not
depend on consent.

## Marketplace and creator kit

Use the promise above as the lead. Show the human flow - friend becomes joinable,
request, approval, shared world - in under 30 seconds before explaining
networking. The source kit must include:

- approved icon/banner/screenshots and a silent-captioned demo source;
- 30-, 100-, and 300-word descriptions using the same promise;
- exact supported-version/loader table and required dependencies;
- privacy, security, support, known-issues, and modpack-redistribution links;
- checksummed GitHub Release links, changelog feed, and rollback notice;
- pack metadata examples and a forecast/support form for large cohorts.

The repository currently provides the product/distribution copy and MIT
redistribution contract in `docs/connect-share.md` plus the ready-to-publish
source copy, metadata, and demo storyboard in
`docs/connect-share-marketplace-kit.md`; final visual assets,
marketplace projects, public demo, creator recruitment, and support staffing are
external launch deliverables.

## Localization and support

English and German in-game journeys ship together today. Add locales by
reachable-player coverage and beta demand, beginning with Brazilian Portuguese,
Spanish, French, Russian, Simplified Chinese, and Japanese. Every locale must
cover the friend request/join, approval, privacy, recovery, compatibility,
failure, and install-handoff journeys; untranslated safety copy blocks that
locale's release.

Publish `docs/connect-share-known-issues.md` with version/loader, symptom, safe workaround,
fixed release, and no secrets. Support requests begin with **Copy safe
diagnostics**; never ask for tokens, invitations, keys, addresses, full friend
files, or recovery archives. Confirmed regressions receive a focused automated
test before the fix and are linked to the staged rollout decision.
