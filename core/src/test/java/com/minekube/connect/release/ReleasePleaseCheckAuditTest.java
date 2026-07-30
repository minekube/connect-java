/*
 * Copyright (c) 2019-2022 Minekube. https://minekube.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * @author Minekube
 * @link https://github.com/minekube/connect-java
 */

package com.minekube.connect.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins release-please's native-check boundary. A manually dispatched workflow already creates one
 * check run per matrix job on the dispatched commit; mirroring that result into another check run
 * and a legacy commit status makes GitHub display the same build multiple times.
 */
class ReleasePleaseCheckAuditTest {

    private static final Path WORKFLOW_PATH =
            Paths.get("..", ".github", "workflows", "release-please.yml");
    private static final Path REPOSITORY_GIT_PATH = Paths.get("..", ".git");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> workflow() throws Exception {
        if (!Files.exists(WORKFLOW_PATH)) {
            if (Files.exists(REPOSITORY_GIT_PATH)) {
                throw new AssertionError(
                        WORKFLOW_PATH + " is missing from the repository checkout");
            }
            assumeTrue(false, WORKFLOW_PATH + " is unavailable outside a repository checkout");
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(WORKFLOW_PATH)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void releasePrAuditsNativeChecksOnTheExactHeadWithoutDuplicatingThem() throws Exception {
        Map<String, Object> workflow = workflow();
        assumeTrue(!workflow.isEmpty());

        Map<String, Object> permissions = (Map<String, Object>) workflow.get("permissions");
        assertEquals("read", permissions.get("checks"),
                "release-please must read native checks without permission to create duplicates");
        assertFalse(permissions.containsKey("statuses"),
                "release-please can still create duplicate legacy statuses");

        Map<String, Object> jobs = (Map<String, Object>) workflow.get("jobs");
        Map<String, Object> releasePlease = (Map<String, Object>) jobs.get("release-please");
        List<Map<String, Object>> steps =
                (List<Map<String, Object>>) releasePlease.get("steps");
        Map<String, Object> validate = steps.stream()
                .filter(step -> "Validate and merge release PR".equals(step.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "release-please is missing its release PR validation step"));
        String script = String.valueOf(validate.get("run"));

        assertFalse(script.contains("repos/${{ github.repository }}/check-runs"),
                "release-please still POSTs a synthetic build check");
        assertFalse(script.contains("statuses/$HEAD_SHA"),
                "release-please still POSTs a legacy build status");

        assertTrue(script.contains("commits/$HEAD_SHA/check-runs?per_page=100"),
                "release-please does not read the checks attached to the captured commit");
        assertTrue(script.contains("startswith($run_url + \"/job/\")"),
                "release-please does not restrict its audit to native jobs from the dispatched run");
        assertTrue(script.contains(".status != \"completed\"")
                        && script.contains(".conclusion != \"success\""),
                "release-please does not reject incomplete or unsuccessful native checks");
        assertTrue(script.contains("CURRENT_HEAD_SHA") && script.contains("!= \"$HEAD_SHA\""),
                "release-please does not refuse a merge when the PR moved after validation");
    }
}
