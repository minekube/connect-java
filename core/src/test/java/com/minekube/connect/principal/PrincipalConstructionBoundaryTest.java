package com.minekube.connect.principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minekube.connect.api.player.principal.VerifiedBedrockPrincipal;
import com.minekube.connect.api.player.principal.VerifiedPrincipal;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrincipalConstructionBoundaryTest {
    @TempDir Path tempDir;

    @Test
    void sealedHierarchyPermitsOnlySdkImplementations() throws Exception {
        assertTrue(VerifiedPrincipal.class.isSealed());
        assertTrue(VerifiedBedrockPrincipal.class.isSealed());
        assertEquals(List.of(VerifiedBedrockPrincipal.class),
                List.of(VerifiedPrincipal.class.getPermittedSubclasses()));
        Class<?> implementation = VerifiedBedrockPrincipal.class.getPermittedSubclasses()[0];
        assertEquals("ImmutableVerifiedBedrockPrincipal", implementation.getSimpleName());
        assertTrue(Modifier.isFinal(implementation.getModifiers()));
        assertFalse(Modifier.isPublic(implementation.getModifiers()));
    }

    @Test
    void hostCannotForgeVerifiedPrincipal() throws Exception {
        Path source = tempDir.resolve("Forged.java");
        Files.writeString(source,
                "import com.minekube.connect.api.player.principal.*;\n"
                        + "final class Forged implements VerifiedBedrockPrincipal {}\n",
                StandardCharsets.UTF_8);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjects(source.toFile());
            boolean success = compiler.getTask(
                    null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path")), null, units).call();
            assertFalse(success);
        }
        assertTrue(diagnostics.getDiagnostics().stream()
                .map(Object::toString)
                .anyMatch(message -> message.contains("sealed")));
    }
}
