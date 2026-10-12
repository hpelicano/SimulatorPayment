package org.simulator.iso.codec;

/**
 * One element decoded from the wire, used by the UI parser view to show exact offsets and raw bytes.
 *
 * @param element "HEADER", "MTI", "BITMAP" or "DE<n>"
 */
public record FieldTrace(String element, String name, int offset, int length, String rawHex, String value) {
}
