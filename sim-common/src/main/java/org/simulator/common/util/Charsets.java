package org.simulator.common.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class Charsets {

    /** IBM EBCDIC code page used by Tandem/NonStop and most card networks. */
    public static final Charset EBCDIC = resolveEbcdic();
    public static final Charset ASCII = StandardCharsets.ISO_8859_1;

    private Charsets() {
    }

    private static Charset resolveEbcdic() {
        for (String name : new String[]{"IBM1047", "Cp1047", "IBM037", "Cp037"}) {
            if (Charset.isSupported(name)) {
                return Charset.forName(name);
            }
        }
        throw new IllegalStateException("No EBCDIC charset available in this JVM");
    }
}
