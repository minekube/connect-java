# Bedrock identity defaults

Connect Edge authenticates Bedrock players with Microsoft/Xbox and signs a short-lived,
endpoint-scoped identity before forwarding the session. A newly installed Connect Java plugin
trusts that Minekube-signed identity without extra operator configuration: it fetches the current
Ed25519 verifier key over HTTPS from Minekube's authoritative metadata endpoint and advertises
`bedrock-identity-v1` after validating the metadata.

This default is appropriate because the Minekube Connect plugin is receiving sessions from the
Minekube Connect edge. The metadata response contains public verifier keys only. The connector
requires HTTPS, rejects URLs containing userinfo or fragments, refuses redirects, and checks that
the metadata issuer is exactly `minekube-connect`.

The default enforcement mode is `warn`. It verifies Bedrock identities and logs failures, but it
never rejects a session. Java sessions without the reserved Bedrock identity property return
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
