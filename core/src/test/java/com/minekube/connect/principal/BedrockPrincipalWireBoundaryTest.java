package com.minekube.connect.principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import minekube.connect.v1alpha1.ConnectLibp2P;
import minekube.connect.v1alpha1.WatchServiceOuterClass;
import org.junit.jupiter.api.Test;

class BedrockPrincipalWireBoundaryTest {
    private static final Set<String> FORBIDDEN = Set.of(
            "xuid", "bedrock_display_name", "linked_java_uuid", "linked_java_name",
            "link_record_id", "jti");

    @Test
    void watchSessionUsesOnlyFrozenOpaqueV2Fields() {
        Descriptor session = WatchServiceOuterClass.Session.getDescriptor();
        assertFields(session, List.of(
                "id:1", "tunnel_service_addr:2", "player:3", "auth:4", "tunnel_transports:5",
                "protocol:6", "endpoint_id:7", "organization_id:8", "connect_session_nonce:9",
                "source_protocol_version:10", "policy_revision:11", "signed_bedrock_principal_v2:12"));
        assertEquals(List.of("payload"), WatchServiceOuterClass.WatchRequest.getDescriptor()
                .getOneofs().stream().map(oneof -> oneof.getName()).toList());
        assertEquals(List.of("payload"), WatchServiceOuterClass.WatchResponse.getDescriptor()
                .getOneofs().stream().map(oneof -> oneof.getName()).toList());
        assertNoRawIdentity(WatchServiceOuterClass.getDescriptor().getMessageTypes());
    }

    @Test
    void libp2pOfferUsesOnlyFrozenOpaqueV2Fields() {
        assertFields(ConnectLibp2P.SessionOffer.getDescriptor(), List.of(
                "session_id:1", "endpoint:2", "player:3", "auth:4", "deadline_unix_ms:5",
                "endpoint_id:6", "endpoint_org_id:7", "protocol:8", "connect_session_nonce:9",
                "source_protocol_version:10", "policy_revision:11", "signed_bedrock_principal_v2:12"));
        assertNoRawIdentity(ConnectLibp2P.getDescriptor().getMessageTypes());
    }

    private static void assertFields(Descriptor descriptor, List<String> expected) {
        assertEquals(expected, descriptor.getFields().stream()
                .map(field -> field.getName() + ":" + field.getNumber()).toList());
    }

    private static void assertNoRawIdentity(List<Descriptor> roots) {
        for (Descriptor root : roots) assertNoRawIdentity(root);
    }

    private static void assertNoRawIdentity(Descriptor descriptor) {
        Set<String> names = descriptor.getFields().stream()
                .map(FieldDescriptor::getName).collect(Collectors.toSet());
        assertTrue(names.stream().noneMatch(FORBIDDEN::contains), descriptor.getFullName());
        for (FieldDescriptor field : descriptor.getFields()) {
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                assertNoRawIdentity(field.getMessageType());
            }
        }
    }
}
