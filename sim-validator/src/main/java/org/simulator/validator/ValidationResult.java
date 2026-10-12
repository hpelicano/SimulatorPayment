package org.simulator.validator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {

    private final List<ValidationIssue> issues = new ArrayList<>();

    public ValidationResult add(ValidationIssue issue) {
        issues.add(issue);
        return this;
    }

    public ValidationResult addAll(ValidationResult other) {
        issues.addAll(other.issues);
        return this;
    }

    public List<ValidationIssue> issues() {
        return Collections.unmodifiableList(issues);
    }

    public boolean passed() {
        return issues.stream().noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
    }

    public long errorCount() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.ERROR).count();
    }

    @Override
    public String toString() {
        return passed() ? "PASSED (" + issues.size() + " notes)" : "FAILED " + issues;
    }
}
