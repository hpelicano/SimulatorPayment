package org.simulator.issuer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issuer response rule: when every configured condition matches the request, the action is applied.
 * Rules are evaluated in list order; the first match wins.
 */
public class ResponseRule {

    private String name;
    private boolean enabled = true;
    private Match match = new Match();
    private Action action = new Action();

    public static class Match {
        /** Regex over the request MTI, e.g. "02.0". */
        private String mti;
        /** Regex over DE3. */
        private String processingCode;
        /** Any of these prefixes must match DE2. */
        private List<String> binPrefixes = new ArrayList<>();
        /** Inclusive amount range over DE4 in minor units. */
        private BigDecimal amountMin;
        private BigDecimal amountMax;
        /** Regex over DE41. */
        private String terminalId;
        /** Field number -> regex. A regex of "!" means the field must be absent. */
        private Map<String, String> fields = new LinkedHashMap<>();

        public String getMti() { return mti; }
        public void setMti(String mti) { this.mti = mti; }
        public String getProcessingCode() { return processingCode; }
        public void setProcessingCode(String processingCode) { this.processingCode = processingCode; }
        public List<String> getBinPrefixes() { return binPrefixes; }
        public void setBinPrefixes(List<String> binPrefixes) { this.binPrefixes = binPrefixes == null ? new ArrayList<>() : binPrefixes; }
        public BigDecimal getAmountMin() { return amountMin; }
        public void setAmountMin(BigDecimal amountMin) { this.amountMin = amountMin; }
        public BigDecimal getAmountMax() { return amountMax; }
        public void setAmountMax(BigDecimal amountMax) { this.amountMax = amountMax; }
        public String getTerminalId() { return terminalId; }
        public void setTerminalId(String terminalId) { this.terminalId = terminalId; }
        public Map<String, String> getFields() { return fields; }
        public void setFields(Map<String, String> fields) { this.fields = fields == null ? new LinkedHashMap<>() : fields; }
    }

    public static class Action {
        private String responseCode;
        /** When set, response codes are returned in round-robin order (overrides responseCode). */
        private List<String> responseCodeSequence = new ArrayList<>();
        private long delayMs;
        /** Random extra delay 0..jitterMs added to delayMs. */
        private long jitterMs;
        /** Do not answer at all (forces a timeout on the acquirer). */
        private boolean noResponse;
        /** Field number -> value (supports ${RANDOM_N:n}, ${REQ:n} = copy of request field n). */
        private Map<String, String> setFields = new LinkedHashMap<>();
        private List<Integer> removeFields = new ArrayList<>();

        public String getResponseCode() { return responseCode; }
        public void setResponseCode(String responseCode) { this.responseCode = responseCode; }
        public List<String> getResponseCodeSequence() { return responseCodeSequence; }
        public void setResponseCodeSequence(List<String> s) { this.responseCodeSequence = s == null ? new ArrayList<>() : s; }
        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
        public long getJitterMs() { return jitterMs; }
        public void setJitterMs(long jitterMs) { this.jitterMs = jitterMs; }
        public boolean isNoResponse() { return noResponse; }
        public void setNoResponse(boolean noResponse) { this.noResponse = noResponse; }
        public Map<String, String> getSetFields() { return setFields; }
        public void setSetFields(Map<String, String> setFields) { this.setFields = setFields == null ? new LinkedHashMap<>() : setFields; }
        public List<Integer> getRemoveFields() { return removeFields; }
        public void setRemoveFields(List<Integer> removeFields) { this.removeFields = removeFields == null ? new ArrayList<>() : removeFields; }
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Match getMatch() { return match; }
    public void setMatch(Match match) { this.match = match == null ? new Match() : match; }
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action == null ? new Action() : action; }

    @Override
    public String toString() {
        return name;
    }
}
