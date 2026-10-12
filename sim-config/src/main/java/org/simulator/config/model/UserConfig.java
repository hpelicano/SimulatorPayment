package org.simulator.config.model;

/** Contents of {@code user.json}: operator profile and UI/workspace preferences. */
public class UserConfig {

    private String username = System.getProperty("user.name");
    private String displayName;
    private String theme = "LIGHT";
    private String language = "es";
    private String workspaceDir;
    /** Days to keep transaction logs in H2 before purge. */
    private int logRetentionDays = 30;
    /** Hash used to verify the master password without storing it. */
    private String masterPasswordCheck;
    /** Expose Prometheus metrics on {@link #metricsPort} at startup. */
    private boolean metricsEnabled;
    private int metricsPort = 9464;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public int getLogRetentionDays() { return logRetentionDays; }
    public void setLogRetentionDays(int logRetentionDays) { this.logRetentionDays = logRetentionDays; }
    public String getMasterPasswordCheck() { return masterPasswordCheck; }
    public void setMasterPasswordCheck(String masterPasswordCheck) { this.masterPasswordCheck = masterPasswordCheck; }
    public boolean isMetricsEnabled() { return metricsEnabled; }
    public void setMetricsEnabled(boolean metricsEnabled) { this.metricsEnabled = metricsEnabled; }
    public int getMetricsPort() { return metricsPort; }
    public void setMetricsPort(int metricsPort) { this.metricsPort = metricsPort; }
}
