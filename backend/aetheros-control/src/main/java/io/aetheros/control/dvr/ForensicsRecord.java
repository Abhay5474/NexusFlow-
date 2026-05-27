package io.aetheros.control.dvr;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "forensics_event",
        indexes = {
                @Index(name = "idx_ts", columnList = "ts"),
                @Index(name = "idx_stage_ts", columnList = "stage,ts")
        })
public class ForensicsRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant ts;

    @Column(length = 36)
    private String connectionId;

    @Column(length = 32, nullable = false)
    private String stage;

    @Column(length = 512)
    private String decision;

    @Column(length = 2048)
    private String tagsJson;

    public Long getId() { return id; }
    public Instant getTs() { return ts; }
    public String getConnectionId() { return connectionId; }
    public String getStage() { return stage; }
    public String getDecision() { return decision; }
    public String getTagsJson() { return tagsJson; }

    public void setTs(Instant ts) { this.ts = ts; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public void setStage(String stage) { this.stage = stage; }
    public void setDecision(String decision) { this.decision = decision; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
}
