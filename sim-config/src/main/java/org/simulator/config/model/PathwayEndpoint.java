package org.simulator.config.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;

public class PathwayEndpoint {

    /** SSH credentials are independent of the Guardian endpoint. */
    private String mode = "SSH";
    private String host = "127.0.0.1";
    private int port = 22;
    private String username;
    private String password;
    private String privateKeyPath;
    private String privateKeyPassphrase;
    private String knownHostsPath;
    private boolean strictHostKeyChecking = true;
    private String serverName = "$PM";
    private List<String> servers = new ArrayList<>(List.of("$PM"));
    private String taclPrompt = "(?m)\\d+>\\s*$";
    /** PATHMON process name, e.g. $B24PM. */
    private String pathmon = "$PM";
    private String prompt = "=";
    private String lineTerminator = "\r\n";
    private int timeoutMs = 15_000;
    /** Command verbs allowed to be sent (whitelist). */
    private List<String> allowedVerbs = new ArrayList<>(List.of("STATUS", "INFO", "START", "STOP", "FREEZE", "THAW", "ALTER", "ABORT", "RESET"));
    /** Verbs that require explicit user confirmation. */
    private List<String> confirmVerbs = new ArrayList<>(List.of("STOP", "FREEZE", "ALTER", "ABORT", "RESET"));

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }
    public String getPrivateKeyPassphrase() { return privateKeyPassphrase; }
    public void setPrivateKeyPassphrase(String privateKeyPassphrase) { this.privateKeyPassphrase = privateKeyPassphrase; }
    public String getKnownHostsPath() { return knownHostsPath; }
    public void setKnownHostsPath(String knownHostsPath) { this.knownHostsPath = knownHostsPath; }
    public boolean isStrictHostKeyChecking() { return strictHostKeyChecking; }
    public void setStrictHostKeyChecking(boolean strictHostKeyChecking) { this.strictHostKeyChecking = strictHostKeyChecking; }
    @JsonProperty("server_name")
    public String getServerName() { return serverName; }
    @JsonProperty("server_name")
    public void setServerName(String serverName) { this.serverName = serverName; }
    public List<String> getServers() { return servers; }
    public void setServers(List<String> servers) { this.servers = servers == null ? new ArrayList<>() : servers; }
    public String getTaclPrompt() { return taclPrompt; }
    public void setTaclPrompt(String taclPrompt) { this.taclPrompt = taclPrompt; }
    /** Resolves configured PATHMON processes, with legacy pathmon as a compatibility fallback. */
    public List<String> serverNames() {
        if (!servers.isEmpty()) return java.util.Collections.unmodifiableList(new ArrayList<>(servers));
        String fallback = serverName == null || serverName.isBlank() ? pathmon : serverName;
        return fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
    }
    public SshEndpoint sshEndpoint() {
        SshEndpoint ep = new SshEndpoint();
        ep.setHost(host);
        ep.setPort(port);
        ep.setUsername(username);
        ep.setPassword(password);
        ep.setPrivateKeyPath(privateKeyPath);
        ep.setPrivateKeyPassphrase(privateKeyPassphrase);
        ep.setKnownHostsPath(knownHostsPath);
        ep.setStrictHostKeyChecking(strictHostKeyChecking);
        ep.setConnectTimeoutMs(timeoutMs);
        ep.setCommandTimeoutMs(timeoutMs);
        return ep;
    }
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getPathmon() { return pathmon; }
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public void setPathmon(String pathmon) { this.pathmon = pathmon; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getLineTerminator() { return lineTerminator; }
    public void setLineTerminator(String lineTerminator) { this.lineTerminator = lineTerminator; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public List<String> getAllowedVerbs() { return allowedVerbs; }
    public void setAllowedVerbs(List<String> allowedVerbs) { this.allowedVerbs = allowedVerbs; }
    public List<String> getConfirmVerbs() { return confirmVerbs; }
    public void setConfirmVerbs(List<String> confirmVerbs) { this.confirmVerbs = confirmVerbs; }
}
