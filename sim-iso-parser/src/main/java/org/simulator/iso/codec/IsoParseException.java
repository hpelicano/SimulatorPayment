package org.simulator.iso.codec;

import org.simulator.common.SimulatorException;

public class IsoParseException extends SimulatorException {

    private final int field;
    private final int offset;

    public IsoParseException(String message, int field, int offset) {
        super(message + (field > 0 ? " [DE" + field + "]" : "") + (offset >= 0 ? " at offset " + offset : ""));
        this.field = field;
        this.offset = offset;
    }

    public IsoParseException(String message, int field, int offset, Throwable cause) {
        super(message + (field > 0 ? " [DE" + field + "]" : "") + (offset >= 0 ? " at offset " + offset : ""), cause);
        this.field = field;
        this.offset = offset;
    }

    public int getField() {
        return field;
    }

    public int getOffset() {
        return offset;
    }
}
