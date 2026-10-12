package org.simulator.iso.dialect;

public enum FieldType {
    /** Numeric digits. */
    N,
    /** Alphabetic. */
    A,
    /** Alphanumeric. */
    AN,
    /** Alphanumeric and special characters. */
    ANS,
    /** Numeric and special characters. */
    NS,
    /** Track 2/3 data: digits plus '=' / 'D' separator. */
    Z,
    /** Binary data, canonical value is an uppercase hex string. Length always expressed in bytes. */
    B;

    public boolean isNumericLike() {
        return this == N || this == Z;
    }
}
