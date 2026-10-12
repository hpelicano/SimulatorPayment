package org.simulator.regression.replay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A traffic capture that can be saved (encrypted) and replayed later, without PIN/security control fields. */
public class Recording {

    private String name;
    private String environment;
    private Instant createdAt = Instant.now();
    private List<RecordedTransaction> items = new ArrayList<>();

    public Recording() {
    }

    public Recording(String name, String environment) {
        this.name = name;
        this.environment = environment;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<RecordedTransaction> getItems() { return items; }
    public void setItems(List<RecordedTransaction> items) { this.items = items == null ? new ArrayList<>() : items; }

    public long durationMs() {
        return items.isEmpty() ? 0 : items.get(items.size() - 1).offsetMs();
    }
}