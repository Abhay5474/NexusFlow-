package io.aetheros.aegis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Project AEGIS — Lock-Free Radix Trie Blocklist Engine.
 *
 * <p>Stores massive domain blocklists (EasyList, EasyPrivacy, Steven Black's
 * hosts file, etc.) in a highly memory-efficient Radix Trie structure.
 * Domain labels are stored <em>reversed</em> (TLD-first) so wildcard suffix
 * matches (e.g., block all *.doubleclick.net) are trivially O(k) lookups.
 *
 * <p>Lookup performance is O(k) where k = number of domain labels, completely
 * independent of blocklist size. The trie is fully lock-free for reads —
 * concurrent {@link #isBlocked(String)} calls never contend.
 *
 * <h3>Supported blocklist formats</h3>
 * <ul>
 *   <li>hosts file format: {@code 0.0.0.0 ads.example.com}</li>
 *   <li>domain-only format: {@code ads.example.com}</li>
 *   <li>Lines starting with {@code #} are comments and skipped</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <ul>
 *   <li>{@link #isBlocked(String)} — fully lock-free, safe for concurrent use</li>
 *   <li>{@link #addDomain(String, String)} — uses ConcurrentHashMap internally, safe</li>
 *   <li>{@link #loadFromStream(InputStream, String)} — call from a single loader thread</li>
 * </ul>
 */
public class RadixTrieBlocklist {

    private static final Logger LOG = System.getLogger(RadixTrieBlocklist.class.getName());

    /** Root of the trie — represents the implicit "." DNS root. */
    private final RadixTrieNode root = new RadixTrieNode();

    /** Total number of blocked domain entries in the trie. */
    private final AtomicLong entryCount  = new AtomicLong(0);

    /** Total number of successful block lookups since startup. */
    private final AtomicLong blockHits   = new AtomicLong(0);

    /** Total number of allow lookups since startup. */
    private final AtomicLong allowHits   = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Insertion
    // -----------------------------------------------------------------------

    /**
     * Inserts a single domain into the blocklist.
     *
     * @param domain   The domain to block (e.g., "ads.doubleclick.net")
     * @param category Category label (e.g., "ADVERTISING", "TRACKER")
     */
    public void addDomain(String domain, String category) {
        if (domain == null || domain.isBlank()) return;
        domain = domain.trim().toLowerCase();

        String[] labels = domain.split("\\.");
        RadixTrieNode current = root;

        // Traverse trie in reverse label order (TLD → subdomain)
        for (int i = labels.length - 1; i >= 0; i--) {
            String label = labels[i];
            if (label.isEmpty()) continue;
            current = current.child(label);
        }

        if (current.terminal.compareAndSet(false, true)) {
            current.category = category;
            entryCount.incrementAndGet();
        }
    }

    /**
     * Loads a blocklist from an {@link InputStream}.
     * Supports hosts-file format and domain-only format.
     *
     * @param in       Input stream of blocklist data
     * @param category Category tag to apply to all entries from this list
     * @return Number of entries added
     * @throws IOException if reading fails
     */
    public long loadFromStream(InputStream in, String category) throws IOException {
        long added = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;

                // Hosts-file format: "0.0.0.0 ads.example.com" or "127.0.0.1 ads.example.com"
                if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                    String[] parts = line.split("\\s+", 2);
                    if (parts.length == 2) {
                        addDomain(parts[1], category);
                        added++;
                    }
                } else if (!line.contains(" ") && line.contains(".")) {
                    // Domain-only format
                    addDomain(line, category);
                    added++;
                }
            }
        }
        LOG.log(Level.INFO, "[AEGIS] Loaded {0} entries (category={1})", added, category);
        return added;
    }

    /**
     * Downloads and loads a remote blocklist URL.
     *
     * @param url      HTTPS URL of the blocklist
     * @param category Category tag
     * @return Number of entries added
     */
    public long loadFromUrl(String url, String category) throws Exception {
        LOG.log(Level.INFO, "[AEGIS] Fetching blocklist: {0}", url);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .GET().timeout(Duration.ofSeconds(30)).build();
        HttpResponse<InputStream> resp = client.send(req,
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " fetching " + url);
        }
        return loadFromStream(resp.body(), category);
    }

    // -----------------------------------------------------------------------
    // Lookup
    // -----------------------------------------------------------------------

    /**
     * Result record for a blocklist lookup.
     *
     * @param blocked  true if the domain (or a parent domain) is blocked
     * @param category Category of the matched rule, or "ALLOWED" if not blocked
     */
    public record LookupResult(boolean blocked, String category) {
        static final LookupResult ALLOWED = new LookupResult(false, "ALLOWED");
    }

    /**
     * Checks whether the given domain is blocked.
     *
     * <p>This is a fully lock-free O(k) trie walk. It also checks ancestor
     * domains (wildcard blocking) — e.g., if "doubleclick.net" is in the
     * blocklist, then "ad.doubleclick.net" is also blocked.
     *
     * @param domain The FQDN to check (e.g., "pagead2.googlesyndication.com")
     * @return {@link LookupResult} indicating block status and category
     */
    public LookupResult isBlocked(String domain) {
        if (domain == null || domain.isBlank()) return LookupResult.ALLOWED;
        domain = domain.trim().toLowerCase();
        // Strip trailing dot (FQDN)
        if (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);

        String[] labels = domain.split("\\.");
        RadixTrieNode current = root;

        // Walk the trie in reverse (TLD → subdomain) to support wildcard ancestor matching
        for (int i = labels.length - 1; i >= 0; i--) {
            String label = labels[i];
            RadixTrieNode next = current.children.get(label);
            if (next == null) {
                // No match at this label
                allowHits.incrementAndGet();
                return LookupResult.ALLOWED;
            }
            // Check if this intermediate node is a terminal (wildcard parent block)
            if (next.terminal.get()) {
                blockHits.incrementAndGet();
                return new LookupResult(true, next.category);
            }
            current = next;
        }

        allowHits.incrementAndGet();
        return LookupResult.ALLOWED;
    }

    /**
     * Returns true if the domain is blocked (convenience wrapper).
     */
    public boolean isBlocked(String domain, String ignored) {
        return isBlocked(domain).blocked();
    }

    // -----------------------------------------------------------------------
    // Stats
    // -----------------------------------------------------------------------

    public long getEntryCount() { return entryCount.get(); }
    public long getBlockHits()  { return blockHits.get(); }
    public long getAllowHits()  { return allowHits.get(); }
}
