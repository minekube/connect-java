# Connect Share HTTPS invite handoff

This is the client/web contract for issue #117. The handoff page is not hosted
by this repository, and Connect Share must not copy an HTTPS form by default
until that page is deployed and verified. A broken install link is worse than
the working signed custom URI and ordinary Direct Connect path.

## Secret boundary

The canonical shape is:

`https://connect.minekube.com/share/#<signed-invitation>`

The signed invitation is carried only in the URL fragment. Browsers do not send
the fragment in the HTTP request, so the origin, CDN, access log, and normal
server analytics never receive it. The page must use a restrictive CSP, no
third-party scripts, `Referrer-Policy: no-referrer`, no service-worker caching
of invite state, and no fragment-bearing links. It validates the invitation
locally before showing any host-provided text or route.

The page leads with **Join your friend**. Transport, endpoint, token, peer, and
address terminology is diagnostics-only.

## Resolution flow

1. Parse, bound, and verify the signed invitation entirely on the recipient.
2. Show safe expired, revoked, malformed, unsupported, and incompatible states
   without echoing the payload.
3. If Connect Share is registered, open the custom URI once and wait for an
   explicit local acknowledgement before offering retry.
4. Otherwise resolve Minecraft version, loader, OS, and supported launcher to
   an allowlisted artifact/dependency manifest fetched without the invitation.
5. Offer Modrinth App, PrismLauncher, CurseForge, and manual paths only where a
   tested adapter exists. Never synthesize shell commands or arbitrary URLs
   from invitation fields.
6. If the host enabled no-mod ingress, retain an ordinary Direct Connect option
   that reveals only the public Connect hostname after local verification.

## One-shot install resume

Before launching an installer, the page creates random one-shot resume state
bound to a digest of the invitation, expected artifact, expiry, and launcher.
The secret invitation remains client-side. A launcher adapter may pass it to the
installed mod through an OS-approved custom-protocol handoff or a short-lived,
owner-only local file. The mod atomically consumes and deletes the state before
opening confirmation. Successful, declined, cancelled, expired, mismatched,
and crashed resumes cannot replay automatically; retry requires an explicit
recipient action.

Do not use browser local storage, query parameters, server sessions, analytics
events, clipboard history, or launcher logs for the invitation.

## Verification gate

Browser/launcher E2E must cover Windows, macOS, and Linux; already installed,
fresh install, dependency install, cancellation, retry, restart, expired,
revoked, incompatible, malicious payload, unavailable launcher, manual
download, and vanilla fallback. Each test verifies that server/CDN/referrer and
launcher logs contain no invitation, capability, token, private address, or
hidden presence.

Client integration is blocked on a real handoff origin, reviewed web source,
published marketplace project IDs, documented launcher adapters, and a signed
resume protocol. Until then the product keeps the working custom invitation and
ordinary server address; it must not emit a dead HTTPS link.
