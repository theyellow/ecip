package io.emcip.admin.api.util;

/** Hygiene for attacker-controlled strings before they enter the immutable audit trail. */
public final class AuditText {

    private static final int MAX_LEN = 256;

    private AuditText() {}

    /**
     * Strips Unicode control (Cc) and format (Cf: bidi/zero-width) characters, then truncates to
     * {@value #MAX_LEN} chars. Returns {@code null} for {@code null} input.
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints()
                .forEach(
                        cp -> {
                            int type = Character.getType(cp);
                            if (type != Character.CONTROL && type != Character.FORMAT) {
                                sb.appendCodePoint(cp);
                            }
                        });
        String cleaned = sb.toString();
        return cleaned.length() > MAX_LEN ? cleaned.substring(0, MAX_LEN) : cleaned;
    }
}
