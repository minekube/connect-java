package com.minekube.connect.identity;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndpointTokenStoreTest {
    private final EndpointTokenStore store = new EndpointTokenStore();

    @TempDir Path tempDir;

    @Test
    void createsPluginCompatibleTokenJson() throws Exception {
        Path file = tempDir.resolve("connect").resolve("token.json");

        String token = store.loadOrCreate(file, Map.of());

        assertTrue(token.startsWith("T-"));
        assertEquals(
                token,
                new Gson().fromJson(Files.readString(file), JsonObject.class)
                        .get("token")
                        .getAsString());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals(Set.of(OWNER_READ, OWNER_WRITE), Files.getPosixFilePermissions(file));
        }
    }

    @Test
    void reusesTheSameToken() throws Exception {
        Path file = tempDir.resolve("token.json");

        String first = store.loadOrCreate(file, Map.of());
        String second = store.loadOrCreate(file, Map.of());

        assertEquals(first, second);
    }

    @Test
    void connectTokenEnvironmentOverridesDisk() throws Exception {
        Path file = tempDir.resolve("token.json");
        store.save(file, "T-disk");

        assertEquals(
                "T-environment",
                store.load(file, Map.of(EndpointTokenStore.ENV_TOKEN, "T-environment"))
                        .orElseThrow());
        assertEquals(
                "T-disk",
                new Gson().fromJson(Files.readString(file), JsonObject.class)
                        .get("token")
                        .getAsString());
    }

    @Test
    void rejectsBlankAndNonPrefixedTokens() throws Exception {
        Path file = tempDir.resolve("token.json");

        assertThrows(IllegalArgumentException.class, () -> store.save(file, ""));
        assertThrows(IllegalArgumentException.class, () -> store.save(file, "dashboard-token"));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.load(file, Map.of(EndpointTokenStore.ENV_TOKEN, " ")));

        Files.writeString(file, "{\"token\":\"not-connect\"}");
        assertThrows(IllegalArgumentException.class, () -> store.load(file, Map.of()));
    }

    @Test
    void atomicallyReplacesToken() throws Exception {
        Path file = tempDir.resolve("token.json");
        store.save(file, "T-before");

        store.save(file, "T-after");

        assertEquals("T-after", store.load(file, Map.of()).orElseThrow());
        try (var files = Files.list(tempDir)) {
            assertEquals(Set.of(file), Set.copyOf(files.toList()));
        }
    }

    @Test
    void redactionNeverContainsTheToken() {
        String token = "T-this-must-never-appear-in-a-log";

        String redacted = EndpointTokenStore.redact(token);

        assertFalse(redacted.contains(token));
        assertEquals("<redacted-connect-token>", redacted);
    }
}
