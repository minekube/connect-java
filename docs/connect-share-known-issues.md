# Connect Share known issues

These are release blockers or limitations for the unmerged Connect Share work
in PR #94. Do not present the mod as generally available until the relevant
item is resolved and its evidence is linked.

| Area | Current limitation | Safe action |
|---|---|---|
| HTTPS invite | The handoff page and launcher-resume protocol are not deployed | Share the signed in-mod friend invitation; hosts with a proven Connect ingress may separately share the ordinary Minecraft address |
| Marketplace install | Modrinth and CurseForge projects/credentials and a public Share release have not been verified | Use the exact locally built artifact and dependencies from `docs/connect-share.md`; do not redistribute an unreviewed snapshot as a stable release |
| Recovery | Offline backup transfers one identity but cannot revoke a lost active device or safely run the same restored identity concurrently | Close the old profile before restoring; if a device is lost, remove/block the old relationship and re-link a new identity |
| Platform matrix | Every supported loader/version has clean packaged two-client join proof on macOS arm64; Windows, Linux, and x86_64 remain deterministic/build-only | Treat the artifacts as prerelease outside the proven platform until those real-client gates pass |
| Localization | English and German are packaged | Do not claim another locale until its complete safety, recovery, compatibility, and failure journeys are reviewed |

Support reports should include **Copy safe diagnostics**, exact Minecraft
version, loader, OS family, and artifact SHA-256. Never request or post an
invitation, endpoint token/name, private key, peer ID, address, `friends.json`,
recovery archive/password, username, world name, or complete mod inventory.
