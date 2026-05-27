package io.aetheros.ids;

/**
 * Immutable record representing a single NIDS threat detection rule.
 *
 * <p>Rules are broadly compatible with Snort/Suricata rule syntax concepts,
 * stored in simplified Java form. Each rule has:
 * <ul>
 *   <li>A unique identifier (e.g., "ET-2019401")</li>
 *   <li>A human-readable description</li>
 *   <li>A severity level</li>
 *   <li>A byte pattern to match in the payload</li>
 *   <li>An action to take on match</li>
 *   <li>Optional port constraint (0 = any port)</li>
 * </ul>
 */
public record ThreatRule(
        String  ruleId,
        String  description,
        Severity severity,
        byte[]  pattern,
        Action  action,
        int     dstPort
) {

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
    public enum Action   { LOG, DROP }

    /**
     * Convenience factory for ASCII pattern rules.
     */
    public static ThreatRule ascii(String id, String desc, Severity sev,
                                   String asciiPattern, Action action, int port) {
        return new ThreatRule(id, desc, sev,
                asciiPattern.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                action, port);
    }

    /**
     * Convenience factory for hex-byte pattern rules.
     *
     * @param hexPattern Hex string WITHOUT spaces (e.g., "00000000")
     */
    public static ThreatRule hex(String id, String desc, Severity sev,
                                 String hexPattern, Action action, int port) {
        byte[] bytes = new byte[hexPattern.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hexPattern.substring(i*2, i*2+2), 16);
        }
        return new ThreatRule(id, desc, sev, bytes, action, port);
    }
}
