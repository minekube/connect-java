package com.minekube.connect.api.player.principal;

/** The closed Bedrock principal v2 subject set. */
public enum SubjectKind {
    BEDROCK_XUID("bedrock_xuid"),
    BEDROCK_LINKED_JAVA("bedrock_linked_java");

    private final String wireName;

    SubjectKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    static SubjectKind fromWireName(String value) throws PrincipalVerificationException {
        for (SubjectKind kind : values()) {
            if (kind.wireName.equals(value)) return kind;
        }
        throw new PrincipalVerificationException(PrincipalError.IDENTITY);
    }
}
