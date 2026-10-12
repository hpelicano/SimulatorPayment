package org.simulator.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Message framing on the TCP stream. */
public enum Framing {
    /** 2-byte big-endian length, not including itself (BASE24 standard). */
    LEN2_BINARY("2 bytes binario (BASE24)"),
    /** 2-byte big-endian length including the 2 length bytes. */
    LEN2_BINARY_INCLUSIVE("2 bytes binario inclusivo"),
    /** 4 ASCII digits length, not including itself. */
    LEN4_ASCII("4 dígitos ASCII"),
    /** VISA: 2-byte length + 2 reserved bytes (0x0000), length excludes the 4 bytes. */
    LEN2_BINARY_HEADER("2 bytes + 2 reservados (VISA)"),
    /** 4-byte big-endian length, not including itself. */
    LEN4_BINARY("4 bytes binario"),
    /** 4 ASCII digits length including the 4 length digits. */
    LEN4_ASCII_INCLUSIVE("4 dígitos ASCII inclusivo"),
    /**
     * No length prefix: every chunk read from the socket is one message and each message is written as is.
     * Only reliable with one message per TCP segment (low volume, no pipelining of large messages).
     */
    NONE("Sin prefijo");

    static final int MAX_FRAME = 65_535;

    private final String label;

    Framing(String label) {
        this.label = label;
    }

    /** Short human readable description for UI selectors. */
    public String label() {
        return label;
    }

    public static Framing parse(String value) {
        return value == null || value.isBlank() ? LEN2_BINARY : Framing.valueOf(value.trim().toUpperCase());
    }

    ChannelHandler decoder() {
        return switch (this) {
            case LEN2_BINARY -> new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 2, 0, 2);
            case LEN2_BINARY_INCLUSIVE -> new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 2, -2, 2);
            case LEN2_BINARY_HEADER -> new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 2, 2, 4);
            case LEN4_BINARY -> new LengthFieldBasedFrameDecoder(MAX_FRAME, 0, 4, 0, 4);
            case LEN4_ASCII -> new AsciiLengthDecoder(false);
            case LEN4_ASCII_INCLUSIVE -> new AsciiLengthDecoder(true);
            case NONE -> new PassThroughDecoder();
        };
    }

    ChannelHandler encoder() {
        Framing self = this;
        return new MessageToByteEncoder<byte[]>() {
            @Override
            protected void encode(ChannelHandlerContext ctx, byte[] msg, ByteBuf out) {
                out.writeBytes(self.frame(msg));
            }
        };
    }

    /** Returns {@code payload} prefixed with this framing's length header. */
    public byte[] frame(byte[] payload) {
        int len = payload.length;
        if (len > MAX_FRAME || (this == LEN4_ASCII && len > 9_999) || (this == LEN4_ASCII_INCLUSIVE && len > 9_995)) {
            throw new IllegalArgumentException("Message too long for framing " + this + ": " + len + " bytes");
        }
        byte[] prefix = switch (this) {
            case LEN2_BINARY -> new byte[]{(byte) (len >> 8), (byte) len};
            case LEN2_BINARY_INCLUSIVE -> new byte[]{(byte) ((len + 2) >> 8), (byte) (len + 2)};
            case LEN2_BINARY_HEADER -> new byte[]{(byte) (len >> 8), (byte) len, 0, 0};
            case LEN4_BINARY -> new byte[]{(byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len};
            case LEN4_ASCII -> String.format("%04d", len).getBytes(StandardCharsets.US_ASCII);
            case LEN4_ASCII_INCLUSIVE -> String.format("%04d", len + 4).getBytes(StandardCharsets.US_ASCII);
            case NONE -> new byte[0];
        };
        byte[] out = new byte[prefix.length + len];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(payload, 0, out, prefix.length, len);
        return out;
    }

    private static final class AsciiLengthDecoder extends ByteToMessageDecoder {
        private final boolean inclusive;

        AsciiLengthDecoder(boolean inclusive) {
            this.inclusive = inclusive;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < 4) {
                return;
            }
            in.markReaderIndex();
            byte[] lenBytes = new byte[4];
            in.readBytes(lenBytes);
            int len;
            try {
                len = Integer.parseInt(new String(lenBytes, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid ASCII length header", e);
            }
            if (inclusive) {
                len -= 4;
                if (len < 0) {
                    throw new IllegalStateException("Invalid inclusive ASCII length header");
                }
            }
            if (in.readableBytes() < len) {
                in.resetReaderIndex();
                return;
            }
            out.add(in.readRetainedSlice(len));
        }
    }

    /** {@link #NONE}: hands over whatever bytes are available as one message. */
    private static final class PassThroughDecoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
        }
    }
}
