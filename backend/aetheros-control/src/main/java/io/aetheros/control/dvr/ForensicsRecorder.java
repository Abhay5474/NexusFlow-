package io.aetheros.control.dvr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aetheros.core.forensics.ForensicsEvent;
import io.aetheros.core.forensics.ForensicsEventPort;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges the in-memory forensics stream onto persistent storage.
 *
 * <p>Writes are batched on a dedicated virtual thread: the data plane
 * publishes synchronously to a bounded queue (drop-oldest on overflow); a
 * drain loop pulls 256-event batches and persists them in one transaction.
 *
 * <p>Retention: a scheduled task evicts events older than the configured
 * window. The DVR is meant for "scrub back N hours," not forever.
 */
@Component
public class ForensicsRecorder {

    private final ForensicsRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<ForensicsEvent> queue = new LinkedBlockingQueue<>(8192);
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread drainer;

    @Value("${aetheros.dvr.retention-hours:24}")
    private int retentionHours;

    public ForensicsRecorder(ForensicsEventPort bus, ForensicsRepository repo) {
        this.repo = repo;
        // Tap the multicast stream. Backpressure: drop-oldest into our local queue.
        bus.stream().subscribe(ev -> {
            if (!queue.offer(ev)) {
                queue.poll();
                queue.offer(ev);
            }
        });
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        drainer = Thread.ofVirtual().name("dvr-drainer").start(this::drainLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (drainer != null) drainer.interrupt();
    }

    private void drainLoop() {
        List<ForensicsEvent> batch = new ArrayList<>(256);
        while (running.get()) {
            try {
                ForensicsEvent head = queue.poll(500, TimeUnit.MILLISECONDS);
                if (head != null) {
                    batch.add(head);
                    queue.drainTo(batch, 255);
                }
                if (!batch.isEmpty()) {
                    persist(batch);
                    batch.clear();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // best-effort recorder; never poison the data plane
                batch.clear();
            }
        }
    }

    @Transactional
    public void persist(List<ForensicsEvent> batch) {
        List<ForensicsRecord> rows = new ArrayList<>(batch.size());
        for (ForensicsEvent e : batch) {
            ForensicsRecord r = new ForensicsRecord();
            r.setTs(e.ts());
            r.setConnectionId(e.connectionId());
            r.setStage(e.stage().name());
            r.setDecision(e.decision());
            try { r.setTagsJson(mapper.writeValueAsString(e.tags())); }
            catch (Exception ex) { r.setTagsJson("{}"); }
            rows.add(r);
        }
        repo.saveAll(rows);
    }

    @Scheduled(fixedDelay = 600_000L)         // every 10 min
    @Transactional
    public void evictOld() {
        repo.deleteByTsBefore(Instant.now().minus(Duration.ofHours(retentionHours)));
    }
}
