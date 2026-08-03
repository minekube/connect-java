# Bedrock identity defaults

Connect Java supports two additive identity paths. Existing files without a
`bedrock-principal` section retain the original `bedrock-identity` v1 behavior and
`enforcement: warn` unchanged. Merely upgrading the plugin does not rewrite such a file or
advertise generation-2 capability.

## Signed principal v2

New generated configuration includes `bedrock-principal.config-generation: 2` and `mode: require`,
with an empty `public-keys` map until host integration supplies usable pins. The v2 consumer accepts
the compact signed principal only from authenticated Watch `Session` or libp2p `SessionOffer` field
12 (`signed_bedrock_principal_v2`); a game-profile property with the reserved v2 name is rejected.
It verifies the closed schema, Ed25519 signature, proposal bindings, time bounds, identity/link
invariants, and an atomic one-use replay key before applying the verifier-selected effective profile.
A linked Java profile always takes precedence over the derived Bedrock profile.

The connector advertises `bedrock-verified-principal-v2` only when generation 2 is in `require`
mode and its trust configuration and static Ed25519 pins are usable. Watch readiness challenges
are answered on their authenticated lease. Libp2p first negotiates `kind-prefixed-v1` framing on
a successful legacy renewal, then answers challenges on that same registration stream. Losing
configuration, keys, framing, or the stream fails closed and suppresses operational readiness.

Static v2 pins use canonical unpadded base64url and contain the raw 32-byte Ed25519 public key:

```yaml
bedrock-principal:
  config-generation: 2
  mode: require
  issuer: minekube-connect
  trust-domain: urn:minekube:connect:production
  audience: urn:minekube:connect:bedrock-principal:v2
  metadata-origin: https://connect.minekube.com
  metadata-path: /.well-known/minekube-connect/bedrock-principal-v2.json
  public-keys:
    "<kid>": "<canonical-unpadded-base64url-raw-ed25519-key>"
```

Verification failures expose only the bounded `PrincipalError` category. Compact envelopes,
nonces, replay IDs, XUIDs, and link material are excluded from exception messages, stack traces,
principal `toString()` output, and sanitized session proposals.

## Legacy v1

Connect Edge authenticates Bedrock players with Microsoft/Xbox and signs a short-lived,
endpoint-scoped identity before forwarding the session. For legacy v1, a newly installed Connect
Java plugin trusts that Minekube-signed identity without extra operator configuration: it validates
the metadata URL syntax at registration and advertises `bedrock-identity-v1`. When a Bedrock session
arrives, it fetches the current Ed25519 verifier key over HTTPS from Minekube's authoritative
metadata endpoint and verifies the metadata lazily for that session.

The legacy v1 `enforcement: warn` default is appropriate because the Minekube Connect plugin is
receiving sessions from the Minekube Connect edge. The metadata response contains public verifier
keys only. The connector requires HTTPS, rejects URLs containing userinfo or fragments, refuses
redirects, and checks that
the metadata issuer is exactly `minekube-connect`.

The default legacy v1 enforcement mode is `warn`. It verifies Bedrock identities and logs
failures, but it never rejects a session. Java sessions without the reserved Bedrock identity
property return
through the Java path without fetching identity keys, logging identity warnings, or changing the
admission decision. Operators can move to `require` only after confirming their Bedrock traffic
verifies successfully.

## Pinning another verifier key

To replace Minekube's metadata source with an operator-controlled Ed25519 key, clear
`metadata-url` and set `public-key`, or populate `public-keys` during rotation:

```yaml
bedrock-identity:
  enforcement: warn
  metadata-url: ""
  public-key: "<base64-encoded Ed25519 public key>"
  public-keys: []
  expected-issuer: minekube-connect
  expected-policy: trusted_bedrock_xuid
```

Static keys are intentionally not a fallback while `metadata-url` is configured. This keeps the
active trust source explicit.
