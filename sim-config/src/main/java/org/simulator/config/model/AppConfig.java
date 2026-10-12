package org.simulator.config.model;

import java.util.ArrayList;
import java.util.List;

/** Root of {@code environments.json}: all Base24 environments and the active one. */
public class AppConfig {

    private String activeEnvironment = "DEV";
    private List<EnvironmentConfig> environments = new ArrayList<>();

    public EnvironmentConfig active() {
        return environments.stream()
                .filter(e -> e.getName().equalsIgnoreCase(activeEnvironment))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Active environment not found: " + activeEnvironment));
    }

    public String getActiveEnvironment() {
        return activeEnvironment;
    }

    public void setActiveEnvironment(String activeEnvironment) {
        this.activeEnvironment = activeEnvironment;
    }

    public List<EnvironmentConfig> getEnvironments() {
        return environments;
    }

    public void setEnvironments(List<EnvironmentConfig> environments) {
        this.environments = environments;
    }
}
