package io.aetheros.aegis;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single node in the lock-free Radix Trie used by {@link RadixTrieBlocklist}.
 *
 * <p>Each node represents one label segment of a reversed domain name
 * (e.g., "com" → "google" → "ads"). Children are stored in a
 * {@link ConcurrentHashMap} for thread-safe reads with no global lock.
 *
 * <p>The {@code terminal} flag marks end-of-pattern nodes that correspond
 * to a blocked domain entry.
 */
public final class RadixTrieNode {

    /** Child nodes keyed by label segment (single DNS label, lowercased). */
    final ConcurrentHashMap<String, RadixTrieNode> children = new ConcurrentHashMap<>();

    /** True if this node represents the final segment of a blocked domain. */
    final AtomicBoolean terminal = new AtomicBoolean(false);

    /** Blocklist category tag (e.g., "ADVERTISING", "TRACKER", "TELEMETRY"). */
    volatile String category = "UNKNOWN";

    /**
     * Returns the child node for {@code label}, creating it if absent.
     * Uses ConcurrentHashMap.computeIfAbsent for atomic insertion.
     */
    RadixTrieNode child(String label) {
        return children.computeIfAbsent(label, k -> new RadixTrieNode());
    }

    /** Returns true if this node has no children. */
    boolean isLeaf() {
        return children.isEmpty();
    }
}
