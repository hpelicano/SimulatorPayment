package org.simulator.iso.dialect;

public enum LengthType {
    FIXED(0),
    LLVAR(2),
    LLLVAR(3),
    LLLLVAR(4);

    private final int digits;

    LengthType(int digits) {
        this.digits = digits;
    }

    /** Number of decimal digits of the length prefix. */
    public int digits() {
        return digits;
    }

    public int maxLength() {
        return (int) Math.pow(10, digits) - 1;
    }
}
