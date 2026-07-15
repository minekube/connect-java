# Bedrock Identity Enforcement Design

This document owns the architectural rationale for Connect Java's Bedrock identity admission path. Exact wire fields are owned by the [Watch schema](../../../core/src/main/proto/com/minekube/connect/v1alpha1/watch_service.proto) and [libp2p schema](../../../core/src/main/proto/com/minekube/connect/v1alpha1/connect_libp2p.proto); operator defaults are owned by the packaged [server](../../../core/src/main/resources/config.yml) and [proxy](../../../core/src/main/resources/proxy-config.yml) configs.

## Trust Boundary

Moxy authenticates the client protocol and signs the Bedrock identity envelope. Connect Java accepts the protocol marker only from authenticated Watch or libp2p session proposals, preserves the legacy unspecified value, and never infers Java from an unknown value. The `bedrock-identity-v1` capability is advertised by both transports only because this implementation includes the marker, verifier, enforcement, and claims lifecycle.

Connect Java verifies the signed issuer, endpoint and organization scope when available, endpoint name, session, protocol, policy, principal, profile binding, validity window, and replay state. The verifier contract is exercised against the immutable Moxy fixture bytes and SHA-256 manifest in [`core/src/test/resources/bedrock-identity-v1`](../../../core/src/test/resources/bedrock-identity-v1/manifest.json). Key metadata is accepted only for the configured issuer and Ed25519 algorithm, supports current and previous rotation keys, and has a bounded stale-if-error window.

## Private Admission Data

The signed envelope and its transport scope property are admission-only data. [`SessionProposal`](../../../core/src/main/java/com/minekube/connect/watch/SessionProposal.java) removes them from the public player profile and stages one private copy for verification. [`BedrockIdentityProfiles`](../../../api/src/main/java/com/minekube/connect/api/player/bedrock/BedrockIdentityProfiles.java) also filters them from platform profile and forwarding paths.

[`VerifiedBedrockIdentityRegistry`](../../../core/src/main/java/com/minekube/connect/bedrock/VerifiedBedrockIdentityRegistry.java) records immutable claims only after successful admission, keys them by session ID, and removes them on rejection or disconnect. It never exposes the raw envelope, signature, nonce, or key material.

## Plugin API

The authoritative plugin contract is [`ConnectApi#getVerifiedBedrockIdentity`](../../../api/src/main/java/com/minekube/connect/api/ConnectApi.java). The default method keeps existing API implementations compatible, while the built-in implementation returns claims only for the currently connected player object whose session passed verification.

This is not Floodgate or Mojang identity emulation. Existing Java and generic offline behavior remains independent of the Bedrock identity path.

## Delivery Boundary

Plugin release, hub image rebuild, and production rollout remain separate operations. This repository can implement and advertise the Connect Java capability, but it cannot by itself establish Moxy routing, owner controls, or end-to-end production completion.
