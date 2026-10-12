package org.simulator.iso.dialect;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Message header preceding the MTI (Base24 "ISO..." header, VISA 22-byte header, HPDH/TPDU, etc.).
 * Optionally the header embeds the total message length ({@code lengthOffset}/{@code lengthSize}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HeaderDef {

    /** Header length in bytes (0 = no header). */
    private int length;
    /** ASCII/EBCDIC: canonical value is text. BINARY: canonical value is hex. */
    private Encoding encoding = Encoding.ASCII;
    private String defaultValue;
    /** Byte offset inside the header where the total length is written (binary big-endian). */
    private Integer lengthOffset;
    private int lengthSize = 2;
    /** Whether the embedded length counts the header bytes too. */
    private boolean lengthIncludesHeader = true;
    /** How the header is built for a response: ECHO, TPDU_SWAP, BASE24, DEFAULT. */
    private String responseRule = "ECHO";

    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }
    public Encoding getEncoding() { return encoding; }
    public void setEncoding(Encoding encoding) { this.encoding = encoding; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public Integer getLengthOffset() { return lengthOffset; }
    public void setLengthOffset(Integer lengthOffset) { this.lengthOffset = lengthOffset; }
    public int getLengthSize() { return lengthSize; }
    public void setLengthSize(int lengthSize) { this.lengthSize = lengthSize; }
    public boolean isLengthIncludesHeader() { return lengthIncludesHeader; }
    public void setLengthIncludesHeader(boolean lengthIncludesHeader) { this.lengthIncludesHeader = lengthIncludesHeader; }
    public String getResponseRule() { return responseRule; }
    public void setResponseRule(String responseRule) { this.responseRule = responseRule; }
}
