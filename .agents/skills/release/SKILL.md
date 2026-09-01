---
name: release
description: Audit the release-PR checks, verify published assets, keep the Modrinth and Hangar publish step contracts intact, and repair a connect-java release that published no assets.
---

# Connect Java release workflow

Commit-prefix rules and the `release-please.yml` to `release.yml` handoff live in the root
`AGENTS.md`; this skill holds the step contracts and repair procedure.

## Release Flow

- `release-please.yml`'s release-PR build is a manual dispatch whose native matrix checks are
  audited on the captured head before merge. Do not mirror those checks into
  synthetic check runs or legacy statuses; the boundary is pinned by
  `core/.../release/ReleasePleaseCheckAuditTest`.
- After creating a release, verify the release is not draft/prerelease unless
  intentionally so, and verify the asset digest/availability:

```sh
gh -R minekube/connect-java release view <version> --json tagName,targetCommitish,isDraft,isPrerelease,assets
curl -I -L --fail https://github.com/minekube/connect-java/releases/download/<version>/connect-velocity.jar
```

- `release.yml`'s "Verify published release assets" step re-reads each published
  release from the API (never the upload step's own output) and requires the
  same positive plugin-jar allowlist as `release-repair.yml`. Pinned by
  `core/.../release/ReleaseAssetVerificationTest`; keep that test's step and
  upload-step names in sync when editing `release.yml`.
- `release.yml`'s "Publish to Modrinth" step publishes the same jars to the
  Modrinth listing (project id `PuSyuNRf`, `minekube-connect`), one version per
  platform because Modrinth runs every validator whose loaders intersect the
  declared loaders against every file in a version. It uploads the runner's
  build output, never the release assets, and confirms each upload by reading
  the stored version back and comparing sha1 and sha512. Its event condition is
  the safety property: without it every push to `main` would publish a
  development build to a public listing without anything going red. Pinned by
  `core/.../release/ReleaseModrinthPublishTest`; keep that test's step names in
  sync when editing `release.yml`. Dispatching `release.yml` at an OLD tag
  publishes that tag to Modrinth - the listing is not a backfill target.
- `release.yml`'s "Publish to Hangar" step publishes to `minekube/Connect` and
  syncs `.github/hangar-description.md`. `HANGAR_API_TOKEN` needs
  `create_version` and `edit_page`. Hangar's platform mapping is Paper jar to
  `PAPER`, Velocity jar to `VELOCITY`, and Bungee jar to `WATERFALL` (Hangar
  has no BungeeCord platform). The step reads accepted platform versions at
  publish time, floors Paper from `plugin.yml` and Velocity at the existing
  3.0 compatibility boundary. The three shaded jars exceed Hangar's
  Cloudflare request limit as one multipart upload, so the version uses
  immutable versioned GitHub release URLs, stores their SHA-256 values in the
  public version description, and verifies GitHub's asset digest plus each
  Hangar download's final bytes, size, content type, and JAR magic. Pinned by
  `core/.../release/ReleaseHangarPublishTest`; keep its step names in sync.

### Repairing a release that published no assets

- Use `release-repair.yml` (default branch, manual dispatch). It builds at the
  JDK that tag's own `release.yml` pinned and uploads only missing or broken
  assets. Never dispatch `release.yml` at an old tag instead: it rewrites the
  live `latest` release, dragging the stable `releases/download/latest/*.jar`
  URLs backwards. Boundary and guards pinned by
  `core/.../release/ReleaseRepairCapabilityTest`; keep its step names in sync.
- A repair EXECUTES old, unreviewed tagged source. Never collapse the workflow's
  read-only `build` / write-only `publish` job boundary or substitute
  step-level token scoping; the workflow comments and
  `ReleaseRepairCapabilityTest` own the exact boundary, artifact-name
  allowlist, race handling, and landed-verification details. Top-level
  `permissions: {}` must remain explicit.
- Asset naming is per-era: tags up to 0.7.0 published version-suffixed jars
  (`connect-spigot-0.6.2.jar`), 0.7.1 onwards publish bare names. The repair
  derives which from the tag's own release workflow, so a repair does not rename.
- `0.6.0` and `0.7.0` are the only zero-asset releases and are **not**
  repairable. Their `bungee/build.gradle.kts` requests `bungeecord-proxy` with
  transitive deps, and `net.md-5:bungeecord-{api,log,protocol,query}` at
  `1.20-R0.3-SNAPSHOT` / `1.21-R0.1-SNAPSHOT` are 404 on every repository those
  tags declare - only `bungeecord-proxy` itself survives upstream. `main` avoids
  this with `includeTransitiveDeps = false`; back-porting that into a tag would
  change what the tag builds, so it is a rewrite, not a repair.
