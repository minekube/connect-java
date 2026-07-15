# Bedrock Identity Enforcement Design

This document owns the architectural rationale for Connect Java's Bedrock identity admission path. Exact wire fields are owned by the [Watch schema](../../../core/src/main/proto/com/minekube/connect/v1alpha1/watch_service.proto) and [libp2p schema](../../../core/src/main/proto/com/minekube/connect/v1alpha1/connect_libp2p.proto); operator defaults are owned by the packaged [server](../../../core/src/main/resources/config.yml) and [proxy](../../../core/src/main/resources/proxy-config.yml) configs.

## Trust Boundary

Moxy authenticates the client protocol and signs the Bedrock identity envelope. Connect Java accepts the protocol marker only from authenticated Watch or libp2p session proposals, preserves the legacy unspecified value, and never infers Java from an unknown value. Both transports derive `bedrock-identity-v1` from one live readiness predicate: enforcement must not be `disabled`, and the configured static or remote key source must contain validated usable keys. A readiness transition reconnects Watch and closes the active libp2p registration so the next registration withdraws or restores the capability.

Connect Java verifies the signed issuer, endpoint and organization scope when available, endpoint name, session, protocol, policy, principal, profile binding, validity window, and replay state. The verifier contract is exercised against the immutable Moxy fixture bytes and SHA-256 manifest in [`core/src/test/resources/bedrock-identity-v1`](../../../core/src/test/resources/bedrock-identity-v1/manifest.json). Remote key metadata is accepted only for the configured issuer and Ed25519 algorithm, supports current and previous rotation keys, and is fetched by a dedicated no-redirect client with a five-second call timeout and 64 KiB body limit. Refresh is single-flight with bounded failure backoff; only validated keys remain usable through the configured hard stale deadline, after which `require` mode fails closed.

## Private Admission Data

The signed envelope and its transport scope property are admission-only data. [`SessionProposal`](../../../core/src/main/java/com/minekube/connect/watch/SessionProposal.java) removes them from its public session immediately and retains at most one private proposal copy until admission staging. [`VerifiedBedrockIdentityRegistry`](../../../core/src/main/java/com/minekube/connect/bedrock/VerifiedBedrockIdentityRegistry.java) then holds a one-shot private profile for verification for at most 30 seconds. Failed connections, rejection, disconnect, replacement, expiry, and platform shutdown discard staged or verified state. [`BedrockIdentityProfiles`](../../../api/src/main/java/com/minekube/connect/api/player/bedrock/BedrockIdentityProfiles.java) also filters the private properties from platform profile and forwarding paths.

The registry records immutable claims only after successful verification, keys them by session ID, and exposes them only while the matching public player object is connected. It never retains claims after rejection or disconnect and never exposes the raw envelope, signature, nonce, or key material.

## Plugin API

The authoritative plugin contract is [`ConnectApi#getVerifiedBedrockIdentity`](../../../api/src/main/java/com/minekube/connect/api/ConnectApi.java). The default method keeps existing API implementations compatible, while the built-in implementation returns claims only for the currently connected player object whose session passed verification.

This is not Floodgate or Mojang identity emulation. Existing Java and generic offline behavior remains independent of the Bedrock identity path.

## Delivery Boundary

Plugin release, hub image rebuild, and production rollout remain separate operations. This repository can implement and advertise the Connect Java capability, but it cannot by itself establish Moxy routing, owner controls, or end-to-end production completion.
