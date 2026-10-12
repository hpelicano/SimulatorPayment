package org.simulator.acquirer;

import org.simulator.validator.ValidationProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A reusable transaction definition. Field values can contain placeholders resolved by
 * {@link TemplateEngine} (e.g. {@code ${STAN}}, {@code ${RRN}}, {@code ${AMOUNT}}).
 */
public class TransactionTemplate {

    private String id;
    private String name;
    private String description;
    private String category;
    /** Null means the dialect of the active endpoint. */
    private String dialect;
    private String mti;
    private String header;
    private Map<String, String> fields = new LinkedHashMap<>();
    /** Default variable values, overridable at send time. */
    private Map<String, String> variables = new LinkedHashMap<>();
    private ValidationProfile validation;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
    public String getMti() { return mti; }
    public void setMti(String mti) { this.mti = mti; }
    public String getHeader() { return header; }
    public void setHeader(String header) { this.header = header; }
    public Map<String, String> getFields() { return fields; }
    public void setFields(Map<String, String> fields) { this.fields = fields == null ? new LinkedHashMap<>() : fields; }
    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables == null ? new LinkedHashMap<>() : variables; }
    public ValidationProfile getValidation() { return validation; }
    public void setValidation(ValidationProfile validation) { this.validation = validation; }

    @Override
    public String toString() {
        return name != null ? name : id;
    }
}
