package org.simulator.iso.dialect;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Definition of one data element. {@code encoding} and {@code lengthEncoding} are optional; when null the dialect
 * defaults apply (numeric/text/length encodings).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldDef {

    private int number;
    private String name;
    private FieldType type = FieldType.ANS;
    private LengthType lengthType = LengthType.FIXED;
    /** Fixed length or maximum length. Digits for N/Z, bytes for B, characters otherwise. */
    private int length;
    private Encoding encoding;
    private Encoding lengthEncoding;
    /** LEFT or RIGHT: where the padding nibble goes for odd BCD values (default LEFT for N, RIGHT for Z). */
    private String padNibble;
    /** Optional description of sub-structure used by the UI: BER_TLV, BASE24_TOKENS, POSITIONAL. */
    private String subFormat;
    /** Fields that must never be persisted/logged in clear. */
    private Boolean sensitive;

    public FieldDef() {
    }

    public FieldDef(int number, String name, FieldType type, LengthType lengthType, int length) {
        this.number = number;
        this.name = name;
        this.type = type;
        this.lengthType = lengthType;
        this.length = length;
    }

    public FieldDef copy() {
        FieldDef f = new FieldDef(number, name, type, lengthType, length);
        f.encoding = encoding;
        f.lengthEncoding = lengthEncoding;
        f.padNibble = padNibble;
        f.subFormat = subFormat;
        f.sensitive = sensitive;
        return f;
    }

    /** Applies non-default attributes of {@code override} on top of this definition. */
    public FieldDef merge(FieldDef override) {
        FieldDef f = copy();
        if (override.name != null) f.name = override.name;
        if (override.type != null) f.type = override.type;
        if (override.lengthType != null) f.lengthType = override.lengthType;
        if (override.length > 0) f.length = override.length;
        if (override.encoding != null) f.encoding = override.encoding;
        if (override.lengthEncoding != null) f.lengthEncoding = override.lengthEncoding;
        if (override.padNibble != null) f.padNibble = override.padNibble;
        if (override.subFormat != null) f.subFormat = override.subFormat;
        if (override.sensitive != null) f.sensitive = override.sensitive;
        return f;
    }

    public boolean isVariable() {
        return lengthType != null && lengthType != LengthType.FIXED;
    }

    public int getNumber() { return number; }
    public void setNumber(int number) { this.number = number; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public FieldType getType() { return type; }
    public void setType(FieldType type) { this.type = type; }
    public LengthType getLengthType() { return lengthType; }
    public void setLengthType(LengthType lengthType) { this.lengthType = lengthType; }
    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }
    public Encoding getEncoding() { return encoding; }
    public void setEncoding(Encoding encoding) { this.encoding = encoding; }
    public Encoding getLengthEncoding() { return lengthEncoding; }
    public void setLengthEncoding(Encoding lengthEncoding) { this.lengthEncoding = lengthEncoding; }
    public String getPadNibble() { return padNibble; }
    public void setPadNibble(String padNibble) { this.padNibble = padNibble; }
    public String getSubFormat() { return subFormat; }
    public void setSubFormat(String subFormat) { this.subFormat = subFormat; }
    public Boolean getSensitive() { return sensitive; }
    public void setSensitive(Boolean sensitive) { this.sensitive = sensitive; }

    @Override
    public String toString() {
        return "DE" + number + " " + name + " " + type + (isVariable() ? " " + lengthType + ".." : " ") + length;
    }
}
