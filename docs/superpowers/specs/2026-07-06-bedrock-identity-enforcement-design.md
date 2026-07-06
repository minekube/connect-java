# Bedrock Identity Enforcement Design

## Goal

Add opt-in Connect Java enforcement for Moxy-signed `minekube:bedrock_identity` profile properties so backend servers can reject Bedrock sessions whose trusted edge identity cannot be verified.

## Scope

This slice adds static-key enforcement only. It does not add network key discovery, key rotation metadata, or public docs. Those are follow-up rollout tasks because they change operational trust and production delivery independently from local enforcement behavior.

## Config

Add a `bedrock-identity` block to `config.yml` and `proxy-config.yml`:

```yaml
bedrock-identity:
  enforcement: disabled
  public-key: ""
  expected-policy: trusted_bedrock_xuid
```

`enforcement` accepts:

- `disabled`: default; no verification or rejection.
- `warn`: verify when a public key is configured and log failures, but allow the session.
- `require`: verify with the configured public key and reject when the envelope is missing, forged, expired, replayed, scope-mismatched, or policy-mismatched.

`public-key` is a base64-encoded Ed25519 public key. The verifier accepts raw 32-byte keys and X.509 encoded keys after decoding. `expected-policy` defaults to `trusted_bedrock_xuid`.

## Architecture

Add a small core enforcement service around the released `BedrockIdentityVerifier`. It receives `ConnectConfig` and `ConnectLogger`, builds verifiers per `LocalSession.Context`, and returns an allow/reject decision. It owns replay cache state so platform hooks do not duplicate verification details.

The service checks endpoint name from `ConnectConfig#getEndpoint`, session ID from the `ConnectPlayer`, protocol `bedrock`, and expected policy from config. Connect Java does not currently receive endpoint ID or org ID in the legacy WatchService session proposal, and libp2p registration does not attach those values to the local login context. This slice therefore extends the verifier API so endpoint ID and org ID are optional expected scope fields: if configured, they are enforced; if omitted, the signed envelope must still contain them but local enforcement does not claim to know their expected values.

The reject reason must be support-safe and must never include the raw signed envelope or key material.

## Platform Hooks

Run enforcement at the earliest existing platform login boundary where `LocalSession.Context` is present:

- Velocity: `PreLoginEvent`, before `forceOfflineMode()` and player cache insertion.
- Bungee: `PreLoginEvent`, before UUID/name rewrite.
- Spigot: `LOGIN_START_PACKET`, before spoofed UUID, profile rewrite, and server login event calls.

In `require` mode, reject the local login and reject the session proposal if the tunnel has not opened. In `warn` mode, log a warning and continue. In `disabled` mode, do nothing.

## Testing

Core tests cover disabled, warn, require success, require missing property, invalid public key, policy mismatch, replay, and support-safe logging. Platform tests cover hook behavior where the repo already has viable test seams, with Spigot covered through `SpigotDataHandlerTest` and core enforcement covered independently for proxy hooks.

## Follow-Ups

Create or update GitHub tracking for:

- public-key discovery and rotation metadata
- carrying endpoint ID/org ID into the local login context so Connect Java can enforce full endpoint/org scope
- production signing-key/public-key rollout
- operator docs explaining official Bedrock/Xbox auth, Moxy identity envelopes, and Connect Java enforcement
