package org.simulator.issuer;

import java.util.ArrayList;
import java.util.List;

/**
 * Issuer configuration: default behavior plus ordered response rules. Serialized as JSON.
 */
public class IssuerRules {

    private String defaultResponseCode = "00";
    private long defaultDelayMs;
    /** Request fields never echoed in the response (sensitive data). */
    private List<Integer> excludeFromResponse = new ArrayList<>(List.of(14, 35, 36, 45, 52, 53, 55));
    /** Generate a random DE38 (authorization id) on approvals. */
    private boolean generateAuthCode = true;
    private List<ResponseRule> rules = new ArrayList<>();

    public String getDefaultResponseCode() { return defaultResponseCode; }
    public void setDefaultResponseCode(String defaultResponseCode) { this.defaultResponseCode = defaultResponseCode; }
    public long getDefaultDelayMs() { return defaultDelayMs; }
    public void setDefaultDelayMs(long defaultDelayMs) { this.defaultDelayMs = defaultDelayMs; }
    public List<Integer> getExcludeFromResponse() { return excludeFromResponse; }
    public void setExcludeFromResponse(List<Integer> v) { this.excludeFromResponse = v == null ? new ArrayList<>() : v; }
    public boolean isGenerateAuthCode() { return generateAuthCode; }
    public void setGenerateAuthCode(boolean generateAuthCode) { this.generateAuthCode = generateAuthCode; }
    public List<ResponseRule> getRules() { return rules; }
    public void setRules(List<ResponseRule> rules) { this.rules = rules == null ? new ArrayList<>() : rules; }
}
