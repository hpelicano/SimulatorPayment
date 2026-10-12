package org.simulator.stress.distributed;

import org.simulator.stress.StressResult;
import org.simulator.stress.StressSample;

import java.util.List;

/** Snapshot returned by {@code GET /status} of an agent. */
public record AgentStatus(String agent, boolean running, List<StressSample> samples, StressResult result, String error) {
}