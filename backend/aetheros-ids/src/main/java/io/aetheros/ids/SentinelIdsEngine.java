package io.aetheros.ids;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project SENTINEL-IDS — Embedded Local NIDS/NIPS Engine.
 *
 * <p>Manages the full threat ruleset lifecycle:
 * <ol>
 *   <li>Load default built-in rules (covering common exploit patterns)</li>
 *   <li>Compile rules into an {@link AhoCorasickMatcher} automaton for O(n) scan</li>
 *   <li>Expose {@link #scan(byte[], int, int, int)} for inline payload inspection</li>
 *   <li>Maintain an in-memory ring buffer of recent {@link AlertRecord}s for the UI</li>
 * </ol>
 *
 * <p>Adding new rules triggers an atomic automaton rebuild — a new AhoCorasick
 * instance is constructed and atomically swapped in without any lock contention
 * on the hot scan path.
 */
public class SentinelIdsEngine {

    private static final Logger LOG = System.getLogger(SentinelIdsEngine.class.getName());

    /** Maximum alerts retained in the in-memory ring. */
    private static final int MAX_ALERTS = 1000;

    /** Thread-safe list of active rules. */
    private final CopyOnWriteArrayList<ThreatRule> rules = new CopyOnWriteArrayList<>();

    /** Compiled automaton — atomically replaced on rule updates. */
    private volatile AhoCorasickMatcher matcher;

    /** Recent alert ring buffer. */
    private final java.util.concurrent.LinkedBlockingDeque<AlertRecord> recentAlerts =
            new java.util.concurrent.LinkedBlockingDeque<>(MAX_ALERTS);

    private final AtomicLong totalScanned = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalLogged  = new AtomicLong(0);

    public record AlertRecord(
            String alertId,
            String ts,
            String ruleId,
            ThreatRule.Severity severity,
            String payloadSnippet,
            ThreatRule.Action action,
            String srcIp,
            int dstPort
    ) {}

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    public SentinelIdsEngine() {
        loadDefaultRules();
        rebuildMatcher();
    }

    private void loadDefaultRules() {
        rules.addAll(List.of(
            // Shellcode NOP sled
            ThreatRule.hex("ET-2019401", "Shellcode NOP sled pattern",
                    ThreatRule.Severity.CRITICAL, "9090909090909090", ThreatRule.Action.DROP, 0),
            // SQL injection UNION SELECT
            ThreatRule.ascii("ET-2001219", "SQL injection UNION SELECT",
                    ThreatRule.Severity.HIGH, "UNION SELECT", ThreatRule.Action.DROP, 80),
            // Reverse shell pattern
            ThreatRule.ascii("ET-2019855", "Directory traversal ../",
                    ThreatRule.Severity.HIGH, "../../../etc/passwd", ThreatRule.Action.DROP, 80),
            // Header injection
            ThreatRule.ascii("ET-2018752", "X-Forwarded-For localhost injection",
                    ThreatRule.Severity.MEDIUM, "X-Forwarded-For: 127.0.0.1", ThreatRule.Action.LOG, 0),
            // Scanner user agent
            ThreatRule.ascii("ET-2001045", "zgrab scanner user agent",
                    ThreatRule.Severity.LOW, "zgrab/", ThreatRule.Action.LOG, 0),
            // Log4Shell CVE-2021-44228
            ThreatRule.ascii("ET-2034647", "Log4Shell JNDI injection attempt",
                    ThreatRule.Severity.CRITICAL, "${jndi:", ThreatRule.Action.DROP, 0),
            // EternalBlue MS17-010
            ThreatRule.hex("ET-2024217", "EternalBlue SMB exploit pattern",
                    ThreatRule.Severity.CRITICAL, "ff534d4272000000", ThreatRule.Action.DROP, 445),
            // SSRF cloud metadata
            ThreatRule.ascii("ET-2029999", "SSRF attempt to cloud metadata",
                    ThreatRule.Severity.HIGH, "169.254.169.254", ThreatRule.Action.DROP, 80),
            // PHP webshell
            ThreatRule.ascii("ET-2016182", "PHP eval webshell",
                    ThreatRule.Severity.CRITICAL, "eval(base64_decode", ThreatRule.Action.DROP, 80),
            // XSS
            ThreatRule.ascii("ET-2000300", "Cross-site scripting <script>",
                    ThreatRule.Severity.MEDIUM, "<script>alert(", ThreatRule.Action.LOG, 0)
        ));
        LOG.log(Level.INFO, "[SENTINEL-IDS] Loaded {0} built-in rules", rules.size());
    }

    // -----------------------------------------------------------------------
    // Rule management
    // -----------------------------------------------------------------------

    public void addRule(ThreatRule rule) {
        rules.add(rule);
        rebuildMatcher(); // Atomic swap
        LOG.log(Level.INFO, "[SENTINEL-IDS] Rule added: {0} — automaton rebuilt ({1} patterns)",
                rule.ruleId(), matcher.getPatternCount());
    }

    private synchronized void rebuildMatcher() {
        AhoCorasickMatcher.Builder builder = new AhoCorasickMatcher.Builder();
        for (ThreatRule rule : rules) {
            builder.add(rule.pattern());
        }
        matcher = builder.build();
    }

    // -----------------------------------------------------------------------
    // Scanning
    // -----------------------------------------------------------------------

    /**
     * Scans a byte buffer for threat patterns.
     *
     * @param buf     Byte array to scan
     * @param offset  Start offset
     * @param length  Bytes to scan
     * @param dstPort Destination port of the connection (used for port-specific rules)
     * @return {@link ScanResult} indicating whether the buffer should be dropped
     */
    public ScanResult scan(byte[] buf, int offset, int length, int dstPort) {
        totalScanned.incrementAndGet();
        AhoCorasickMatcher m = matcher; // volatile read — no lock needed
        List<AhoCorasickMatcher.Match> matches = m.search(buf, offset, length);

        for (AhoCorasickMatcher.Match match : matches) {
            if (match.patternIndex() >= rules.size()) continue;
            ThreatRule rule = rules.get(match.patternIndex());

            // Port constraint check
            if (rule.dstPort() != 0 && rule.dstPort() != dstPort) continue;

            // Extract a small payload snippet for the alert
            int snippetStart = Math.max(0, match.endOffset() - rule.pattern().length);
            int snippetLen   = Math.min(64, length - snippetStart);
            String snippet   = snippetLen > 0
                    ? new String(buf, offset + snippetStart, snippetLen, java.nio.charset.StandardCharsets.ISO_8859_1)
                    : "(empty)";

            AlertRecord alert = new AlertRecord(
                    UUID.randomUUID().toString().substring(0, 8),
                    java.time.Instant.now().toString(),
                    rule.ruleId(),
                    rule.severity(),
                    snippet,
                    rule.action(),
                    "unknown",
                    dstPort
            );

            // Add to ring (drop oldest if full)
            if (!recentAlerts.offerLast(alert)) {
                recentAlerts.pollFirst();
                recentAlerts.offerLast(alert);
            }

            if (rule.action() == ThreatRule.Action.DROP) {
                totalDropped.incrementAndGet();
                return ScanResult.DROP(rule);
            } else {
                totalLogged.incrementAndGet();
            }
        }
        return ScanResult.PASS;
    }

    // -----------------------------------------------------------------------
    // Result & stats
    // -----------------------------------------------------------------------

    public record ScanResult(boolean drop, ThreatRule matchedRule) {
        static final ScanResult PASS = new ScanResult(false, null);
        static ScanResult DROP(ThreatRule rule) { return new ScanResult(true, rule); }
    }

    public List<AlertRecord> getRecentAlerts() { return new ArrayList<>(recentAlerts); }
    public List<ThreatRule>  getRules()         { return List.copyOf(rules); }
    public long getTotalScanned() { return totalScanned.get(); }
    public long getTotalDropped() { return totalDropped.get(); }
    public long getTotalLogged()  { return totalLogged.get(); }
}
