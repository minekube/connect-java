# Connect Share Release and Operations Plan

**Goal:** Make every repository-owned release, operations, security, launch, and
HTTPS-handoff requirement in epic #93 explicit and enforceable without claiming
that external services or reviews already exist.

**Architecture:** Keep the Minecraft client free of a telemetry or web-service
dependency until those services have reviewed schemas and real endpoints. Put
release gates in GitHub Actions, stable human contracts in `docs/`, and map each
external dependency to an owner, verification artifact, and issue criterion.

## Task 1: Distribution and provenance

- [x] Verify the six-adapter release workflow fails closed before publication.
- [x] Add artifact provenance/signing only through a supported GitHub primitive.
- [ ] Verify the resulting attestations against a disposable prerelease. This
  is an external credentialed product gate and remains recorded in evidence.
- [x] Preserve exact loader/version names, dependency metadata, checksums, and
  the 63 MiB release budget.

## Task 2: Global fallback operations and security

- [x] Define regional SLOs, error budgets, allowed observability fields, load
  distributions, capacity math, rate-limit principles, chaos gates, runbooks,
  rollback, incident communication, and cost protection.
- [x] Threat-model invitations, imported endpoint credentials, admission,
  social identity, recovery, diagnostics, relays, and update distribution.
- [x] Identify infrastructure tests and independent review as external gates;
  never convert a document into a production-readiness claim.

## Task 3: Staged launch and measurement

- [x] Define launch/pause/rollback/graduation gates and beta cohorts.
- [x] Define a strict opt-in aggregate metrics schema with no stable social
  identity, graph, invitation, token, key, address, username, world, chat, or
  complete inventory fields.
- [x] Provide marketplace and creator-kit source copy, localization process,
  support loop, and known-issues contract.

## Task 4: HTTPS handoff boundary

- [x] Specify a fragment-only invite transport, local verification, one-shot
  resume state, safe launcher adapters, vanilla fallback, CSP/referrer policy,
  expiry/revocation handling, and browser/launcher E2E matrix.
- [x] Do not emit a default HTTPS invite until a deployed handoff page is
  independently verified at the configured origin.

## Task 5: Evidence and handoff

- [x] Extend the acceptance matrix for #117, #118, and #119.
- [ ] Run Markdown/link checks available in the repository, workflow syntax
  checks, targeted tests, the broader build, and `git diff --check`.
- [ ] Push reviewed commits to PR #94; comment on each issue with completed
  repository work and exact external gates. Keep the PR unmerged.
