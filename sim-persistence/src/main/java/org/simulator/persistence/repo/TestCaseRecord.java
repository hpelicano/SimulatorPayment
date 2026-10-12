package org.simulator.persistence.repo;

import java.time.Instant;

public record TestCaseRecord(
        Long id,
        String suiteId,
        String name,
        String dialect,
        String requestJson,
        String expectedRc,
        String ignoreFields,
        String tags,
        String source,
        boolean enabled,
        Instant createdAt) {

    public static TestCaseRecord of(String suiteId, String name, String dialect, String requestJson, String expectedRc) {
        return new TestCaseRecord(null, suiteId, name, dialect, requestJson, expectedRc, null, null, "MANUAL", true, null);
    }

    public TestCaseRecord withSource(String newSource) {
        return new TestCaseRecord(id, suiteId, name, dialect, requestJson, expectedRc, ignoreFields, tags,
                newSource, enabled, createdAt);
    }
}
