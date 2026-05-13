package io.aetheros.bandshifter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void initiallyFull() {
        TokenBucket b = new TokenBucket(1000, 100);
        assertThat(b.tryConsume(1000)).isTrue();
        assertThat(b.tryConsume(1)).isFalse();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        TokenBucket b = new TokenBucket(100, 1000);   // 1 token per ms
        assertThat(b.tryConsume(100)).isTrue();
        Thread.sleep(50);
        assertThat(b.tryConsume(40)).isTrue();        // ~50 refilled
    }
}
