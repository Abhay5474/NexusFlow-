package io.aetheros.ids;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aho-Corasick multi-pattern string matching automaton.
 *
 * <p>Builds a finite state machine from a set of byte patterns that can
 * simultaneously scan a byte stream for ALL patterns in a single O(n) pass,
 * where n is the length of the text. This is the standard algorithm used by
 * production NIDS systems (Snort/Suricata) for wire-speed signature matching.
 *
 * <h3>Time complexity</h3>
 * <ul>
 *   <li>Build time: O(Σ |pattern_i|)</li>
 *   <li>Search time: O(|text| + |matches|)</li>
 * </ul>
 *
 * <p>Thread safety: the automaton is immutable after construction. Multiple
 * threads may call {@link #search(byte[], int, int)} concurrently without
 * synchronization.
 */
public final class AhoCorasickMatcher {

    /** Alphabet size — full byte range */
    private static final int ALPHA = 256;

    // Trie node storage (flat int arrays for cache efficiency)
    private final int[][] go;       // go[state][char] → next state
    private final int[]   fail;     // failure function
    private final int[]   output;   // output[state] = pattern index (or -1)
    private final List<byte[]> patterns;
    private final int numStates;

    private AhoCorasickMatcher(int[][] go, int[] fail, int[] output,
                                List<byte[]> patterns, int numStates) {
        this.go        = go;
        this.fail      = fail;
        this.output    = output;
        this.patterns  = patterns;
        this.numStates = numStates;
    }

    // -----------------------------------------------------------------------
    // Builder
    // -----------------------------------------------------------------------

    public static final class Builder {
        private final List<byte[]> patterns = new ArrayList<>();

        /** Adds a byte pattern to the automaton. */
        public Builder add(byte[] pattern) {
            if (pattern != null && pattern.length > 0)
                patterns.add(pattern.clone());
            return this;
        }

        /** Builds the immutable Aho-Corasick automaton. */
        public AhoCorasickMatcher build() {
            if (patterns.isEmpty()) {
                // Degenerate case — return a no-op matcher
                return new AhoCorasickMatcher(new int[1][ALPHA], new int[1], new int[]{-1},
                        List.of(), 1);
            }

            // Estimate max states = total pattern bytes + 1 (root)
            int maxStates = patterns.stream().mapToInt(p -> p.length).sum() + 1;
            int[][] go    = new int[maxStates][ALPHA];
            int[]   fail  = new int[maxStates];
            int[]   out   = new int[maxStates];

            // Init go with -1 (no transition)
            for (int[] row : go) Arrays.fill(row, -1);
            Arrays.fill(out, -1);

            // Phase 1: build trie
            int numStates = 1; // state 0 = root
            for (int pi = 0; pi < patterns.size(); pi++) {
                byte[] pat = patterns.get(pi);
                int cur = 0;
                for (byte b : pat) {
                    int c = b & 0xFF;
                    if (go[cur][c] == -1) {
                        go[cur][c] = numStates++;
                    }
                    cur = go[cur][c];
                }
                out[cur] = pi;
            }

            // Fill missing root transitions with self-loop
            for (int c = 0; c < ALPHA; c++) {
                if (go[0][c] == -1) go[0][c] = 0;
            }

            // Phase 2: compute failure function via BFS
            Deque<Integer> queue = new ArrayDeque<>();
            for (int c = 0; c < ALPHA; c++) {
                int s = go[0][c];
                if (s != 0) {
                    fail[s] = 0;
                    queue.add(s);
                }
            }

            while (!queue.isEmpty()) {
                int r = queue.poll();
                for (int c = 0; c < ALPHA; c++) {
                    int s = go[r][c];
                    if (s == -1) {
                        // Fill goto with failure-function fallback
                        go[r][c] = go[fail[r]][c];
                    } else {
                        fail[s] = go[fail[r]][c];
                        // Propagate output
                        if (out[s] == -1 && out[fail[s]] != -1) {
                            out[s] = out[fail[s]];
                        }
                        queue.add(s);
                    }
                }
            }

            return new AhoCorasickMatcher(go, fail, out, patterns, numStates);
        }
    }

    // -----------------------------------------------------------------------
    // Search
    // -----------------------------------------------------------------------

    /**
     * Result of a pattern match.
     *
     * @param patternIndex Index into the original pattern list
     * @param endOffset    Byte offset in the text where the match ends (exclusive)
     */
    public record Match(int patternIndex, int endOffset) {}

    /**
     * Searches {@code text[offset..offset+length)} for all patterns.
     * Returns a list of matches in order of discovery.
     *
     * @param text   Byte array to search
     * @param offset Start offset in {@code text}
     * @param length Number of bytes to search
     * @return All matches found (may be empty)
     */
    public List<Match> search(byte[] text, int offset, int length) {
        List<Match> result = new ArrayList<>();
        int state = 0;
        int end   = offset + length;

        for (int i = offset; i < end; i++) {
            int c = text[i] & 0xFF;
            state = go[state][c];
            if (output[state] != -1) {
                result.add(new Match(output[state], i + 1));
            }
        }
        return result;
    }

    /** @return Number of patterns in the automaton. */
    public int getPatternCount() { return patterns.size(); }
}
