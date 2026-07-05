package com.minekube.connect.api.player.bedrock;

public class BedrockIdentityVerificationException extends Exception {
    public BedrockIdentityVerificationException(String message) {
        super(message);
    }

    public BedrockIdentityVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
