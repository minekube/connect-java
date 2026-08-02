# Connect Share known issues

These are release blockers or limitations for the unmerged Connect Share work
in PR #94. Do not present the mod as generally available until the relevant
item is resolved and its evidence is linked.

| Area | Current limitation | Safe action |
|---|---|---|
| No-mod fallback | A vanilla client reaches the public Connect edge, but the tested edge did not deliver a session proposal to the local host, so admission and gameplay did not begin | Use two modded clients on the proven direct path; service owners must resolve and prove the edge/session boundary before advertising vanilla joining |
| HTTPS invite | The handoff page and launcher-resume protocol are not deployed | Share the signed in-mod friend invitation; hosts with a proven Connect ingress may separately share the ordinary Minecraft address |
| Marketplace install | Modrinth and CurseForge projects/credentials and a public Share release have not been verified | Use the exact locally built artifact and dependencies from `docs/connect-share.md`; do not redistribute an unreviewed snapshot as a stable release |
| Recovery | Offline backup transfers one identity but cannot revoke a lost active device or safely run the same restored identity concurrently | Close the old profile before restoring; if a device is lost, remove/block the old relationship and re-link a new identity |
| Platform matrix | Clean packaged direct-join product proof exists for Fabric 26.2 on macOS arm64; the remaining loader/version/OS/architecture matrix is deterministic only | Treat other artifacts as prerelease until their real-client startup and join gates pass |
| Localization | English and German are packaged | Do not claim another locale until its complete safety, recovery, compatibility, and failure journeys are reviewed |

Support reports should include **Copy safe diagnostics**, exact Minecraft
version, loader, OS family, and artifact SHA-256. Never request or post an
invitation, endpoint token/name, private key, peer ID, address, `friends.json`,
recovery archive/password, username, world name, or complete mod inventory.
