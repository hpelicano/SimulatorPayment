package org.simulator.iso.dialect;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A message format definition loaded from JSON. Dialects may {@code extends} another dialect and override
 * encodings, header and individual fields.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageDialect {

    private String id;
    private String name;
    private String description;
    @JsonProperty("extends")
    private String extendsId;
    private HeaderDef header;
    private Encoding mtiEncoding;
    private Encoding bitmapEncoding;
    private Encoding numericEncoding;
    private Encoding textEncoding;
    private Encoding lengthEncoding;
    /** Recommended transport framing for this network (informative). */
    private String framing;
    /** Keyed by field number as string (JSON friendly). */
    private Map<String, FieldDef> fields = new LinkedHashMap<>();

    @JsonIgnore
    private final Map<Integer, FieldDef> resolved = new TreeMap<>();

    /** Merges parent (already resolved) into this dialect. */
    void resolve(MessageDialect parent) {
        if (parent != null) {
            if (header == null) header = parent.header;
            if (mtiEncoding == null) mtiEncoding = parent.mtiEncoding;
            if (bitmapEncoding == null) bitmapEncoding = parent.bitmapEncoding;
            if (numericEncoding == null) numericEncoding = parent.numericEncoding;
            if (textEncoding == null) textEncoding = parent.textEncoding;
            if (lengthEncoding == null) lengthEncoding = parent.lengthEncoding;
            if (framing == null) framing = parent.framing;
            resolved.putAll(parent.resolved);
        }
        if (header == null) header = new HeaderDef();
        if (mtiEncoding == null) mtiEncoding = Encoding.ASCII;
        if (bitmapEncoding == null) bitmapEncoding = Encoding.BINARY;
        if (numericEncoding == null) numericEncoding = Encoding.ASCII;
        if (textEncoding == null) textEncoding = Encoding.ASCII;
        if (lengthEncoding == null) lengthEncoding = Encoding.ASCII;
        fields.forEach((k, def) -> {
            int n = Integer.parseInt(k.replaceAll("\\D", ""));
            FieldDef base = resolved.get(n);
            FieldDef merged = base == null ? def.copy() : base.merge(def);
            merged.setNumber(n);
            if (merged.getType() == null) merged.setType(FieldType.ANS);
            if (merged.getLengthType() == null) merged.setLengthType(LengthType.FIXED);
            resolved.put(n, merged);
        });
    }

    public FieldDef field(int number) {
        return resolved.get(number);
    }

    @JsonIgnore
    public Map<Integer, FieldDef> resolvedFields() {
        return Collections.unmodifiableMap(resolved);
    }

    public Encoding effectiveEncoding(FieldDef f) {
        if (f.getEncoding() != null) {
            return f.getEncoding();
        }
        if (f.getType() == FieldType.B) {
            return Encoding.BINARY;
        }
        return f.getType().isNumericLike() ? numericEncoding : textEncoding;
    }

    public Encoding effectiveLengthEncoding(FieldDef f) {
        return f.getLengthEncoding() != null ? f.getLengthEncoding() : lengthEncoding;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getExtendsId() { return extendsId; }
    public void setExtendsId(String extendsId) { this.extendsId = extendsId; }
    public HeaderDef getHeader() { return header; }
    public void setHeader(HeaderDef header) { this.header = header; }
    public Encoding getMtiEncoding() { return mtiEncoding; }
    public void setMtiEncoding(Encoding mtiEncoding) { this.mtiEncoding = mtiEncoding; }
    public Encoding getBitmapEncoding() { return bitmapEncoding; }
    public void setBitmapEncoding(Encoding bitmapEncoding) { this.bitmapEncoding = bitmapEncoding; }
    public Encoding getNumericEncoding() { return numericEncoding; }
    public void setNumericEncoding(Encoding numericEncoding) { this.numericEncoding = numericEncoding; }
    public Encoding getTextEncoding() { return textEncoding; }
    public void setTextEncoding(Encoding textEncoding) { this.textEncoding = textEncoding; }
    public Encoding getLengthEncoding() { return lengthEncoding; }
    public void setLengthEncoding(Encoding lengthEncoding) { this.lengthEncoding = lengthEncoding; }
    public String getFraming() { return framing; }
    public void setFraming(String framing) { this.framing = framing; }
    public Map<String, FieldDef> getFields() { return fields; }
    public void setFields(Map<String, FieldDef> fields) { this.fields = fields; }

    @Override
    public String toString() {
        return id;
    }
}
