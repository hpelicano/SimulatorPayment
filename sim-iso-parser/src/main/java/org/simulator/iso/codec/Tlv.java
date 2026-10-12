package org.simulator.iso.codec;

import org.simulator.common.util.Hex;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** BER-TLV parser/builder for EMV data (DE55). Canonical value is hex. */
public final class Tlv {

    private Tlv() {
    }

    public record Entry(String tag, int length, String value) {
    }

    /** Offset is relative to the decoded field payload, not the ISO message or its length prefix. */
    public record Detail(String tag, int length, String value, int offset, String path, boolean constructed) {
        public String description() {
            return switch (tag) {
                case "4F", "9F06" -> "Application Identifier (AID)";
                case "50" -> "Application Label";
                case "57" -> "Track 2 Equivalent Data";
                case "5A" -> "Application PAN";
                case "5F20" -> "Cardholder Name";
                case "5F24" -> "Application Expiration Date";
                case "5F25" -> "Application Effective Date";
                case "5F28" -> "Issuer Country Code";
                case "5F2A" -> "Transaction Currency Code";
                case "5F34" -> "PAN Sequence Number";
                case "61" -> "Application Template";
                case "6F" -> "File Control Information (FCI) Template";
                case "70" -> "EMV Record Template";
                case "71" -> "Issuer Script Template 1";
                case "72" -> "Issuer Script Template 2";
                case "77" -> "Response Message Template Format 2";
                case "80" -> "Response Message Template Format 1";
                case "82" -> "Application Interchange Profile (AIP)";
                case "84" -> "Dedicated File (DF) Name";
                case "86" -> "Issuer Script Command";
                case "87" -> "Application Priority Indicator";
                case "8A" -> "Authorization Response Code";
                case "8E" -> "Cardholder Verification Method (CVM) List";
                case "91" -> "Issuer Authentication Data";
                case "94" -> "Application File Locator (AFL)";
                case "95" -> "Terminal Verification Results (TVR)";
                case "9A" -> "Transaction Date";
                case "9B" -> "Transaction Status Information (TSI)";
                case "9C" -> "Transaction Type";
                case "9F02" -> "Amount, Authorized";
                case "9F03" -> "Amount, Other";
                case "9F09" -> "Application Version Number (Terminal)";
                case "9F10" -> "Issuer Application Data (IAD)";
                case "9F12" -> "Application Preferred Name";
                case "9F1A" -> "Terminal Country Code";
                case "9F1E" -> "Interface Device Serial Number";
                case "9F26" -> "Application Cryptogram";
                case "9F27" -> "Cryptogram Information Data (CID)";
                case "9F33" -> "Terminal Capabilities";
                case "9F34" -> "CVM Results";
                case "9F35" -> "Terminal Type";
                case "9F36" -> "Application Transaction Counter (ATC)";
                case "9F37" -> "Unpredictable Number";
                case "9F41" -> "Transaction Sequence Counter";
                case "9F53" -> "Transaction Category Code";
                case "9F66" -> "Terminal Transaction Qualifiers (TTQ)";
                case "9F6C" -> "Card Transaction Qualifiers (CTQ)";
                case "A5" -> "FCI Proprietary Template";
                case "BF0C" -> "FCI Issuer Discretionary Data";
                default -> "Tag propietario / no catalogado";
            };
        }
    }

    public static List<Entry> parse(String hex) {
        byte[] d = Hex.decode(hex);
        List<Detail> details = new ArrayList<>();
        parse(d, 0, d.length, 55, "", 0, false, details);
        List<Entry> out = new ArrayList<>();
        for (Detail detail : details) {
            out.add(new Entry(detail.tag(), detail.length(), detail.value()));
        }
        return out;
    }

    /** Pre-order traversal, preserving duplicate tags and the parent path of constructed values. */
    public static List<Detail> parseDetailed(String hex, int field) {
        byte[] d = Hex.decode(hex);
        List<Detail> out = new ArrayList<>();
        parse(d, 0, d.length, field, "", 0, true, out);
        return out;
    }

    private static void parse(byte[] d, int startOffset, int end, int field, String parent, int depth,
                              boolean recursive, List<Detail> out) {
        if (depth > 32) {
            throw new IsoParseException("TLV nesting exceeds 32 levels", field, startOffset);
        }
        int i = startOffset;
        while (i < end) {
            // EMV permits padding bytes between data objects.
            if (d[i] == 0 || (d[i] & 0xFF) == 0xFF) {
                i++;
                continue;
            }
            int start = i;
            boolean constructed = (d[i] & 0x20) != 0;
            boolean multiByte = (d[i] & 0x1F) == 0x1F;
            i++;
            if (multiByte) {
                int next;
                do {
                    if (i >= end) {
                        throw new IsoParseException("Truncated TLV tag", field, start);
                    }
                    next = d[i++] & 0xFF;
                } while ((next & 0x80) != 0);
            }
            String tag = Hex.encode(Arrays.copyOfRange(d, start, i));
            if (i >= end) {
                throw new IsoParseException("Missing TLV length for tag " + tag, field, i);
            }
            long len = d[i++] & 0xFF;
            if ((len & 0x80) != 0) {
                int n = (int) (len & 0x7F);
                if (n == 0 || n > 4) {
                    throw new IsoParseException("Unsupported TLV length for tag " + tag, field, i - 1);
                }
                if (n > end - i) {
                    throw new IsoParseException("Truncated TLV length for tag " + tag, field, i);
                }
                len = 0;
                for (int k = 0; k < n; k++) {
                    len = (len << 8) | (d[i++] & 0xFF);
                }
            }
            if (len > end - i) {
                throw new IsoParseException("TLV value truncated for tag " + tag, field, i);
            }
            int valueEnd = i + (int) len;
            String path = parent.isEmpty() ? tag : parent + "/" + tag;
            out.add(new Detail(tag, (int) len, Hex.encode(Arrays.copyOfRange(d, i, valueEnd)), start, path, constructed));
            if (recursive && constructed) {
                parse(d, i, valueEnd, field, path, depth + 1, true, out);
            }
            i = valueEnd;
        }
    }

    public static String build(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Entry e : entries) {
            out.writeBytes(Hex.decode(e.tag()));
            byte[] v = Hex.decode(e.value());
            if (v.length < 0x80) {
                out.write(v.length);
            } else if (v.length <= 0xFF) {
                out.write(0x81);
                out.write(v.length);
            } else {
                out.write(0x82);
                out.write(v.length >> 8);
                out.write(v.length);
            }
            out.writeBytes(v);
        }
        return Hex.encode(out.toByteArray());
    }
}
