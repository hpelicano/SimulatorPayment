package org.simulator.iso.codec;

import org.simulator.common.util.Bcd;
import org.simulator.common.util.Charsets;
import org.simulator.common.util.Hex;
import org.simulator.iso.dialect.Encoding;
import org.simulator.iso.dialect.FieldDef;
import org.simulator.iso.dialect.FieldType;
import org.simulator.iso.dialect.LengthType;
import org.simulator.iso.dialect.MessageDialect;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Encodes/decodes a single data element according to its {@link FieldDef} and the dialect defaults.
 * <p>Logical length semantics: digits for BCD numerics, bytes for B fields, characters otherwise.</p>
 */
final class FieldCodec {

    private FieldCodec() {
    }

    static void encode(MessageDialect dialect, FieldDef def, String rawValue, ByteArrayOutputStream out) {
        Encoding enc = dialect.effectiveEncoding(def);
        String value = def.isVariable() ? rawValue : padFixed(def, rawValue);
        int n = def.getNumber();

        byte[] body;
        int logicalLength;
        if (def.getType() == FieldType.B) {
            if (!value.matches("[0-9A-Fa-f]*") || value.length() % 2 != 0) {
                throw new IsoParseException("Binary field must be an even-length hex string", n, -1);
            }
            logicalLength = value.length() / 2;
            body = enc == Encoding.BINARY || enc == Encoding.BCD ? Hex.decode(value) : value.toUpperCase().getBytes(charset(enc));
        } else if (enc == Encoding.BCD) {
            logicalLength = value.length();
            body = rightPadNibble(def) ? Bcd.encodeRightPadded(value, 'F') : Bcd.encode(value);
        } else if (enc == Encoding.BINARY) {
            logicalLength = value.length() / 2;
            body = Hex.decode(value);
        } else {
            logicalLength = value.length();
            body = value.getBytes(charset(enc));
        }

        if (def.isVariable()) {
            if (logicalLength > def.getLength()) {
                throw new IsoParseException("Value length " + logicalLength + " exceeds max " + def.getLength(), n, -1);
            }
            out.writeBytes(encodeLength(def.getLengthType(), logicalLength, dialect.effectiveLengthEncoding(def), n));
        } else if (logicalLength != def.getLength()) {
            throw new IsoParseException("Fixed field length " + logicalLength + " != " + def.getLength(), n, -1);
        }
        out.writeBytes(body);
    }

    /** @return decoded value and the offset right after the field */
    static Decoded decode(MessageDialect dialect, FieldDef def, byte[] data, int offset) {
        int n = def.getNumber();
        int pos = offset;
        int logicalLength;
        if (def.isVariable()) {
            Encoding lenEnc = dialect.effectiveLengthEncoding(def);
            int prefixBytes = prefixSize(def.getLengthType(), lenEnc);
            ensure(data, pos, prefixBytes, n);
            logicalLength = decodeLength(def.getLengthType(), lenEnc, data, pos, n);
            pos += prefixBytes;
            if (logicalLength > def.getLength()) {
                throw new IsoParseException("Declared length " + logicalLength + " exceeds max " + def.getLength(), n, offset);
            }
        } else {
            logicalLength = def.getLength();
        }

        Encoding enc = dialect.effectiveEncoding(def);
        String value;
        int bodyBytes;
        if (def.getType() == FieldType.B) {
            if (enc == Encoding.BINARY || enc == Encoding.BCD) {
                bodyBytes = logicalLength;
                ensure(data, pos, bodyBytes, n);
                value = Hex.encode(Arrays.copyOfRange(data, pos, pos + bodyBytes));
            } else {
                bodyBytes = logicalLength * 2;
                ensure(data, pos, bodyBytes, n);
                value = new String(data, pos, bodyBytes, charset(enc)).toUpperCase();
            }
        } else if (enc == Encoding.BCD) {
            bodyBytes = Bcd.byteLength(logicalLength);
            ensure(data, pos, bodyBytes, n);
            value = rightPadNibble(def) ? Bcd.decodeRightPadded(data, pos, logicalLength) : Bcd.decode(data, pos, logicalLength);
        } else if (enc == Encoding.BINARY) {
            bodyBytes = logicalLength;
            ensure(data, pos, bodyBytes, n);
            value = Hex.encode(Arrays.copyOfRange(data, pos, pos + bodyBytes));
        } else {
            bodyBytes = logicalLength;
            ensure(data, pos, bodyBytes, n);
            value = new String(data, pos, bodyBytes, charset(enc));
        }
        return new Decoded(value, pos + bodyBytes);
    }

    record Decoded(String value, int next) {
    }

    static Charset charset(Encoding enc) {
        return enc == Encoding.EBCDIC || enc == Encoding.HEX_EBCDIC ? Charsets.EBCDIC : Charsets.ASCII;
    }

    private static boolean rightPadNibble(FieldDef def) {
        if (def.getPadNibble() != null) {
            return "RIGHT".equalsIgnoreCase(def.getPadNibble());
        }
        return def.getType() == FieldType.Z;
    }

    private static String padFixed(FieldDef def, String value) {
        int len = def.getLength();
        if (def.getType() == FieldType.B) {
            return value;
        }
        if (value.length() >= len) {
            return value;
        }
        if (def.getType() == FieldType.N) {
            return "0".repeat(len - value.length()) + value;
        }
        return value + " ".repeat(len - value.length());
    }

    static int prefixSize(LengthType lt, Encoding enc) {
        return switch (enc) {
            case BCD -> Bcd.byteLength(lt.digits());
            case BINARY -> lt == LengthType.LLVAR ? 1 : 2;
            default -> lt.digits();
        };
    }

    private static byte[] encodeLength(LengthType lt, int length, Encoding enc, int field) {
        if (length > lt.maxLength()) {
            throw new IsoParseException("Length " + length + " does not fit " + lt, field, -1);
        }
        String digits = String.format("%0" + lt.digits() + "d", length);
        return switch (enc) {
            case BCD -> Bcd.encode(digits);
            case BINARY -> lt == LengthType.LLVAR
                    ? new byte[]{(byte) length}
                    : new byte[]{(byte) (length >> 8), (byte) length};
            default -> digits.getBytes(charset(enc));
        };
    }

    private static int decodeLength(LengthType lt, Encoding enc, byte[] data, int pos, int field) {
        try {
            return switch (enc) {
                case BCD -> Integer.parseInt(Bcd.decode(data, pos, Bcd.byteLength(lt.digits()) * 2));
                case BINARY -> lt == LengthType.LLVAR
                        ? data[pos] & 0xFF
                        : ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                default -> Integer.parseInt(new String(data, pos, lt.digits(), charset(enc)));
            };
        } catch (NumberFormatException e) {
            throw new IsoParseException("Invalid length prefix", field, pos, e);
        }
    }

    private static void ensure(byte[] data, int pos, int len, int field) {
        if (pos + len > data.length) {
            throw new IsoParseException("Message truncated: need " + len + " bytes, have " + (data.length - pos), field, pos);
        }
    }
}
