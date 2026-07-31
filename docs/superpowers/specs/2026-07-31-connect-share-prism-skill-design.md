# Connect Share Prism E2E Skill Design

## Purpose

Preserve the non-obvious procedure for testing Connect Share with two real
Prism Launcher clients so future agents can reproduce friend presence, join
approval, direct transport, Connect fallback, and Minecraft login failures.

## Location and discovery

Create the versioned repository skill at:

```text
.agents/skills/connect-share-prism-e2e/
├── SKILL.md
└── agents/openai.yaml
```

The skill description will trigger for Connect Share live testing, Prism
installation and launch, two-client friend joining, direct-versus-Connect route
diagnosis, Minecraft login diagnosis, and updating the reusable E2E procedure.

## Contents

Keep `SKILL.md` concise and procedural. It will require agents to:

1. Work from the isolated Connect Share worktree and read `share/AGENTS.md`.
2. Run only one Gradle invocation in a worktree at a time.
3. Build and install the exact same 26.2 artifact in both Prism instances.
4. Launch distinct host and guest identities with Prism's `--profile`,
   `--offline`, `--world`, and `--server` arguments.
5. Prove discovery, confirmed-friend activity, Minecraft status, join request,
   approval, and a real `joined the game` log line as separate gates.
6. Use a fresh direct target for status and gameplay because the current proxy
   is one-shot.
7. Diagnose readiness and pipeline failures with logs, `dns-sd`, and `jcmd`.
8. Preserve the offline-versus-online authentication invariant.
9. Restore temporary friend auto-approval and leave both test profiles in a
   safe state.
10. Promote genuinely reusable discoveries back into the skill and
    `share/AGENTS.md`, without recording machine-specific paths or secrets.

The skill will point to `PrismFriendJoinE2ETest` as the executable harness. It
will not duplicate that test or add another shell script.

## Validation

- Generate `agents/openai.yaml` with the skill-creator helper.
- Run `quick_validate.py` against the completed skill directory.
- Confirm the skill contains no endpoint tokens, friend capabilities, account
  credentials, or absolute user-specific paths.
- Commit the skill separately so it remains auditable.

## Non-goals

- Do not install the skill globally; the repository owns this knowledge.
- Do not automate Minecraft UI clicks.
- Do not replace deterministic unit and integration tests with the live E2E.
- Do not encode the current Prism instance names as universal defaults.
