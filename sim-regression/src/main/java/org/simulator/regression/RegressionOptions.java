package org.simulator.regression;

import java.util.HashSet;
import java.util.Set;

/** Options of a regression run. */
public class RegressionOptions {

    /** Regenerate STAN, RRN, DE7, DE12, DE13 so re-sent transactions are not duplicates. */
    private boolean refreshDynamicFields = true;
    /** Parallel test cases (1 = sequential, deterministic order). */
    private int concurrency = 1;
    private boolean stopOnFirstFailure;
    private long delayBetweenMs;
    /** Fields excluded globally from echo checks (in addition to the per-case ignore list). */
    private Set<Integer> ignoreFields = new HashSet<>();
    /** Only run cases whose tags contain this value (null = all). */
    private String tagFilter;

    public boolean isRefreshDynamicFields() { return refreshDynamicFields; }
    public RegressionOptions setRefreshDynamicFields(boolean v) { this.refreshDynamicFields = v; return this; }
    public int getConcurrency() { return concurrency; }
    public RegressionOptions setConcurrency(int v) { this.concurrency = Math.max(1, v); return this; }
    public boolean isStopOnFirstFailure() { return stopOnFirstFailure; }
    public RegressionOptions setStopOnFirstFailure(boolean v) { this.stopOnFirstFailure = v; return this; }
    public long getDelayBetweenMs() { return delayBetweenMs; }
    public RegressionOptions setDelayBetweenMs(long v) { this.delayBetweenMs = Math.max(0, v); return this; }
    public Set<Integer> getIgnoreFields() { return ignoreFields; }
    public RegressionOptions setIgnoreFields(Set<Integer> v) { this.ignoreFields = v == null ? new HashSet<>() : v; return this; }
    public String getTagFilter() { return tagFilter; }
    public RegressionOptions setTagFilter(String v) { this.tagFilter = v; return this; }
}
