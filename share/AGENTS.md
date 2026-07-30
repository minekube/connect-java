# Connect Share Kotlin Agent Instructions

These instructions apply to every file under `share/`.

## Arrow Is the Default Kotlin Toolkit

Connect Share uses [Arrow](https://github.com/arrow-kt/arrow) as the preferred
toolkit for functional domain modeling, typed errors, validation, concurrency,
resource safety, resilience, and immutable data transformations. Before writing
a custom abstraction in one of those areas, check Arrow's
[library reference](https://arrow-kt.io/learn/quickstart/libs/) and use the
Arrow equivalent when it fits.

Do not recreate capabilities Arrow already provides:

- Model expected domain failures with `Raise<E>` inside cohesive workflows and
  `Either<E, A>` at module or asynchronous boundaries. Reserve exceptions for
  defects, cancellation, and genuinely exceptional infrastructure failures.
- Use `ensure`, `ensureNotNull`, `zipOrAccumulate`, `mapOrAccumulate`, and
  `NonEmptyList` for parsing and validation instead of hand-written error
  collectors or fail-fast exception chains.
- Use `Option` when absence is part of the domain and must be explicit. Keep
  nullable values at Fabric, Minecraft, Java, JSON, or other interop edges, then
  convert them at the boundary.
- Use `resourceScope`, `Resource`, or Arrow AutoClose utilities for acquired
  tunnels, channels, embedded Connect runtimes, and other lifetimes that require
  ordered cleanup. Cancellation must never skip release.
- Use Arrow Fx Coroutines operators such as `parZip`, `parMap`, and race
  operators when they express intended structured concurrency more directly
  than custom coroutine orchestration.
- Use Arrow Resilience schedules, retry policies, and circuit breakers when the
  feature needs those behaviors; do not grow custom retry loops.
- Use Arrow Optics for repeated or deeply nested immutable updates instead of
  copy-chain helpers. Add the Optics/KSP dependency only once such updates exist.
- Use Arrow STM only when several pieces of concurrent state must change as one
  invariant-preserving transaction. Do not substitute it for a simple atomic or
  immutable state flow.
- Prefer Arrow's non-empty collections, combinators, and function utilities
  over equivalent local wrappers.

This is a preference for the appropriate Arrow abstraction, not a requirement
to wrap every Kotlin expression. Plain data classes, sealed interfaces,
collections, `when`, and structured coroutines remain idiomatic. Minecraft and
Fabric callback signatures stay native at their boundaries, and no Arrow type
may cross the Java Connect Core public API unless that API is deliberately
redesigned for Kotlin.

## Dependency Discipline

- Pin the stable Arrow stack version once in `Versions.arrowVersion` and import
  the `arrow-stack` BOM. Do not put independent Arrow versions in module builds.
- `share:common` exposes `arrow-core` because its typed outcomes are part of the
  Kotlin domain API. Runtime-specific modules keep additional Arrow libraries
  as implementation dependencies unless their types are intentionally public.
- Add an Arrow module when the code uses its capability. Do not add the entire
  Arrow ecosystem speculatively.
- Preserve coroutine cancellation. Never catch `CancellationException` as a
  typed domain error.

## Tests

- Assert both sides of typed outcomes and every accumulated validation error.
- For managed resources, test release on success, typed failure, exception, and
  cancellation.
- For retries or parallel operators, use deterministic virtual-time tests; no
  real sleeps.
