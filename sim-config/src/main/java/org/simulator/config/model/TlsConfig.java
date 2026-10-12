package org.simulator.config.model;

import java.util.List;

public class TlsConfig {

    private boolean enabled = false;
    private List<String> protocols = List.of("TLSv1.3", "TLSv1.2");
    private List<String> cipherSuites = List.of();
    /** Client certificate for mutual TLS (PKCS12 or JKS). */
    private String keyStorePath;
    private String keyStorePassword;
    private String keyStoreType = "PKCS12";
    private String trustStorePath;
    private String trustStorePassword;
    private String trustStoreType = "PKCS12";
    private boolean verifyHostname = true;
    /** Lab only: accept any server certificate. */
    private boolean trustAll = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getProtocols() { return protocols; }
    public void setProtocols(List<String> protocols) { this.protocols = protocols; }
    public List<String> getCipherSuites() { return cipherSuites; }
    public void setCipherSuites(List<String> cipherSuites) { this.cipherSuites = cipherSuites; }
    public String getKeyStorePath() { return keyStorePath; }
    public void setKeyStorePath(String keyStorePath) { this.keyStorePath = keyStorePath; }
    public String getKeyStorePassword() { return keyStorePassword; }
    public void setKeyStorePassword(String keyStorePassword) { this.keyStorePassword = keyStorePassword; }
    public String getKeyStoreType() { return keyStoreType; }
    public void setKeyStoreType(String keyStoreType) { this.keyStoreType = keyStoreType; }
    public String getTrustStorePath() { return trustStorePath; }
    public void setTrustStorePath(String trustStorePath) { this.trustStorePath = trustStorePath; }
    public String getTrustStorePassword() { return trustStorePassword; }
    public void setTrustStorePassword(String trustStorePassword) { this.trustStorePassword = trustStorePassword; }
    public String getTrustStoreType() { return trustStoreType; }
    public void setTrustStoreType(String trustStoreType) { this.trustStoreType = trustStoreType; }
    public boolean isVerifyHostname() { return verifyHostname; }
    public void setVerifyHostname(boolean verifyHostname) { this.verifyHostname = verifyHostname; }
    public boolean isTrustAll() { return trustAll; }
    public void setTrustAll(boolean trustAll) { this.trustAll = trustAll; }
}
