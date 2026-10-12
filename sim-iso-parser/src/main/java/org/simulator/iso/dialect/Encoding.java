package org.simulator.iso.dialect;

public enum Encoding {
    ASCII,
    EBCDIC,
    /** Packed BCD (2 digits per byte). */
    BCD,
    /** Raw bytes (B fields as hex in the canonical model; lengths as big-endian integers). */
    BINARY,
    /** Bitmap only: 16 hex characters in ASCII per bitmap. */
    HEX_ASCII,
    /** Bitmap only: 16 hex characters in EBCDIC per bitmap. */
    HEX_EBCDIC
}
