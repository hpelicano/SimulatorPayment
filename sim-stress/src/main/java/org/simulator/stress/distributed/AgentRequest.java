package org.simulator.stress.distributed;

import org.simulator.stress.LoadProfile;
import org.simulator.stress.SlaPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Work order sent by the coordinator to each agent: the agent's share of the load plus what to send. */
public class AgentRequest {

    private LoadProfile profile = new LoadProfile();
    private SlaPolicy sla = SlaPolicy.none();
    private String templateId = "purchase";
    private Map<String, String> variables = new LinkedHashMap<>();

    public LoadProfile getProfile() { return profile; }
    public AgentRequest setProfile(LoadProfile profile) { this.profile = profile; return this; }
    public SlaPolicy getSla() { return sla; }
    public AgentRequest setSla(SlaPolicy sla) { this.sla = sla == null ? SlaPolicy.none() : sla; return this; }
    public String getTemplateId() { return templateId; }
    public AgentRequest setTemplateId(String templateId) { this.templateId = templateId; return this; }
    public Map<String, String> getVariables() { return variables; }
    public AgentRequest setVariables(Map<String, String> variables) { this.variables = variables == null ? new LinkedHashMap<>() : variables; return this; }
}