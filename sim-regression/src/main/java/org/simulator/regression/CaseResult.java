package org.simulator.regression;

import org.simulator.common.event.TransactionEvent.Outcome;
import org.simulator.validator.ValidationIssue;

import java.util.List;

/** Result of one regression test case. */
public record CaseResult(
        Long testCaseId,
        String name,
        boolean passed,
        String expectedRc,
        String actualRc,
        Outcome outcome,
        long latencyMicros,
        List<ValidationIssue> issues,
        String requestJson,
        String responseJson,
        String error) {

    public String summary() {
        if (error != null) {
            return error;
        }
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
                .map(ValidationIssue::toString).reduce((a, b) -> a + "; " + b).orElse("OK");
    }
}
