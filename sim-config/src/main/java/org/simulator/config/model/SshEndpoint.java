package org.simulator.config.model;

public class SshEndpoint {

    private String host = "127.0.0.1";
    private int port = 22;
    private String username;
    /** Plain or ENC(...) encrypted password. */
    private String password;
    private String privateKeyPath;
    private String privateKeyPassphrase;
    private String knownHostsPath;
    private boolean strictHostKeyChecking = true;
    private int connectTimeoutMs = 10_000;
    private int commandTimeoutMs = 60_000;
    /** Default Guardian volume/subvolume, e.g. $DATA1.B24DATA. */
    private String defaultSubvolume;

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
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getCommandTimeoutMs() { return commandTimeoutMs; }
    public void setCommandTimeoutMs(int commandTimeoutMs) { this.commandTimeoutMs = commandTimeoutMs; }
    public String getDefaultSubvolume() { return defaultSubvolume; }
    public void setDefaultSubvolume(String defaultSubvolume) { this.defaultSubvolume = defaultSubvolume; }
}
