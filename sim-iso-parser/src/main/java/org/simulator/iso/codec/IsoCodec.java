package org.simulator.iso.codec;

import org.simulator.common.util.Bcd;
import org.simulator.common.util.Hex;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.dialect.Encoding;
import org.simulator.iso.dialect.FieldDef;
import org.simulator.iso.dialect.HeaderDef;
import org.simulator.iso.dialect.MessageDialect;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Packs/unpacks {@link IsoMessage}s to/from wire bytes for a given {@link MessageDialect}.
 * Thread-safe and stateless: one instance per dialect can be shared.
 */
public final class IsoCodec {

    private final MessageDialect dialect;

    public IsoCodec(MessageDialect dialect) {
        this.dialect = dialect;
    }

    public MessageDialect dialect() {
        return dialect;
    }

    public byte[] pack(IsoMessage msg) {
        if (msg.getMti() == null || !msg.getMti().matches("\\d{4}")) {
            throw new IsoParseException("MTI must be 4 digits: " + msg.getMti(), 0, -1);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        HeaderDef h = dialect.getHeader();
        if (h.getLength() > 0) {
            out.writeBytes(encodeHeader(h, msg.getHeader() != null ? msg.getHeader() : h.getDefaultValue()));
        }
        out.writeBytes(encodeMti(msg.getMti()));

        long primary = 0;
        long secondary = 0;
        boolean needSecondary = msg.fields().keySet().stream().anyMatch(f -> f > 64);
        if (needSecondary) {
            primary |= 1L << 63;
        }
        for (int f : msg.fields().keySet()) {
            if (f <= 64) {
                primary |= 1L << (64 - f);
            } else if (f <= 128) {
                secondary |= 1L << (128 - f);
            } else {
                throw new IsoParseException("Fields above 128 are not supported by this dialect", f, -1);
            }
        }
        out.writeBytes(encodeBitmap(primary));
        if (needSecondary) {
            out.writeBytes(encodeBitmap(secondary));
        }

        for (Map.Entry<Integer, String> e : msg.fields().entrySet()) {
            FieldDef def = dialect.field(e.getKey());
            if (def == null) {
                throw new IsoParseException("Field not defined in dialect " + dialect.getId(), e.getKey(), -1);
            }
            FieldCodec.encode(dialect, def, e.getValue(), out);
        }
        byte[] result = out.toByteArray();
        patchHeaderLength(h, result);
        return result;
    }

    public IsoMessage unpack(byte[] data) {
        return unpack(data, null);
    }

    /**
     * @param trace optional list receiving every decoded element with its offset (for debugging/UI)
     */
    public IsoMessage unpack(byte[] data, List<FieldTrace> trace) {
        IsoMessage msg = new IsoMessage();
        msg.setDialect(dialect.getId());
        int pos = 0;

        HeaderDef h = dialect.getHeader();
        if (h.getLength() > 0) {
            require(data, pos, h.getLength(), "header");
            byte[] hb = Arrays.copyOfRange(data, 0, h.getLength());
            msg.setHeader(decodeHeader(h, hb));
            addTrace(trace, "HEADER", "Header", pos, hb, msg.getHeader());
            pos += h.getLength();
        }

        int mtiLen = dialect.getMtiEncoding() == Encoding.BCD ? 2 : 4;
        require(data, pos, mtiLen, "MTI");
        String mti = dialect.getMtiEncoding() == Encoding.BCD
                ? Bcd.decode(data, pos, 4)
                : new String(data, pos, 4, FieldCodec.charset(dialect.getMtiEncoding()));
        msg.setMti(mti);
        addTrace(trace, "MTI", "Message Type Indicator", pos, Arrays.copyOfRange(data, pos, pos + mtiLen), mti);
        pos += mtiLen;

        int bmLen = dialect.getBitmapEncoding() == Encoding.BINARY ? 8 : 16;
        require(data, pos, bmLen, "primary bitmap");
        long primary = decodeBitmap(data, pos);
        addTrace(trace, "BITMAP", "Primary bitmap", pos, Arrays.copyOfRange(data, pos, pos + bmLen), String.format("%016X", primary));
        pos += bmLen;
        long secondary = 0;
        if ((primary & (1L << 63)) != 0) {
            require(data, pos, bmLen, "secondary bitmap");
            secondary = decodeBitmap(data, pos);
            addTrace(trace, "BITMAP", "Secondary bitmap", pos, Arrays.copyOfRange(data, pos, pos + bmLen), String.format("%016X", secondary));
            pos += bmLen;
        }

        for (int f = 2; f <= 128; f++) {
            boolean present = f <= 64 ? (primary & (1L << (64 - f))) != 0 : (secondary & (1L << (128 - f))) != 0;
            if (!present) {
                continue;
            }
            FieldDef def = dialect.field(f);
            if (def == null) {
                throw new IsoParseException("Bitmap references field not defined in dialect " + dialect.getId(), f, pos);
            }
            FieldCodec.Decoded d = FieldCodec.decode(dialect, def, data, pos);
            msg.set(f, d.value());
            addTrace(trace, "DE" + f, def.getName(), pos, Arrays.copyOfRange(data, pos, d.next()), d.value());
            pos = d.next();
        }
        if (pos != data.length) {
            throw new IsoParseException((data.length - pos) + " trailing bytes after last field", 0, pos);
        }
        return msg;
    }

    private byte[] encodeHeader(HeaderDef h, String value) {
        if (value == null) {
            throw new IsoParseException("Header value required (no default defined)", 0, -1);
        }
        byte[] b = h.getEncoding() == Encoding.BINARY ? Hex.decode(value) : value.getBytes(FieldCodec.charset(h.getEncoding()));
        if (b.length != h.getLength()) {
            throw new IsoParseException("Header must be " + h.getLength() + " bytes but is " + b.length, 0, -1);
        }
        return b;
    }

    private static String decodeHeader(HeaderDef h, byte[] b) {
        return h.getEncoding() == Encoding.BINARY ? Hex.encode(b) : new String(b, FieldCodec.charset(h.getEncoding()));
    }

    private static void patchHeaderLength(HeaderDef h, byte[] msg) {
        if (h.getLength() == 0 || h.getLengthOffset() == null) {
            return;
        }
        int total = h.isLengthIncludesHeader() ? msg.length : msg.length - h.getLength();
        int off = h.getLengthOffset();
        for (int i = h.getLengthSize() - 1; i >= 0; i--) {
            msg[off + i] = (byte) total;
            total >>= 8;
        }
    }

    private byte[] encodeMti(String mti) {
        return dialect.getMtiEncoding() == Encoding.BCD ? Bcd.encode(mti) : mti.getBytes(FieldCodec.charset(dialect.getMtiEncoding()));
    }

    private byte[] encodeBitmap(long bitmap) {
        if (dialect.getBitmapEncoding() == Encoding.BINARY) {
            byte[] b = new byte[8];
            for (int i = 0; i < 8; i++) {
                b[i] = (byte) (bitmap >>> (56 - 8 * i));
            }
            return b;
        }
        return String.format("%016X", bitmap).getBytes(FieldCodec.charset(dialect.getBitmapEncoding()));
    }

    private long decodeBitmap(byte[] data, int pos) {
        if (dialect.getBitmapEncoding() == Encoding.BINARY) {
            long v = 0;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (data[pos + i] & 0xFF);
            }
            return v;
        }
        String hex = new String(data, pos, 16, FieldCodec.charset(dialect.getBitmapEncoding()));
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new IsoParseException("Invalid hex bitmap '" + hex + "'", 0, pos, e);
        }
    }

    private static void require(byte[] data, int pos, int len, String what) {
        if (pos + len > data.length) {
            throw new IsoParseException("Message truncated reading " + what, 0, pos);
        }
    }

    private static void addTrace(List<FieldTrace> trace, String element, String name, int offset, byte[] raw, String value) {
        if (trace != null) {
            trace.add(new FieldTrace(element, name, offset, raw.length, Hex.encode(raw), value));
        }
    }

    /** Convenience: decode returning the trace list. */
    public List<FieldTrace> trace(byte[] data) {
        List<FieldTrace> t = new ArrayList<>();
        try {
            unpack(data, t);
        } catch (IsoParseException e) {
            t.add(new FieldTrace("ERROR", e.getMessage(), e.getOffset(), 0, "", ""));
        }
        return t;
    }
}
