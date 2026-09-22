package com.alpas.ainativesearchrankingoptimizationplatform.features;

import com.alpas.ainativesearchrankingoptimizationplatform.ml.ClickModel;
import com.alpas.ainativesearchrankingoptimizationplatform.platform.PlatformException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class FeatureStore {
    public record Counts(UUID adId, long impressions, long clickedImpressions) {
        public Counts {
            if (adId == null) throw new IllegalArgumentException("Feature ad ID is required");
            ClickModel.features(0, impressions, clickedImpressions);
        }
    }
    public record Publication(UUID id, String schemaVersion, Instant windowStart, Instant cutoff,
                              String sourceId, List<Counts> rows) {
        public Publication {
            if (windowStart!=null) windowStart=windowStart.truncatedTo(ChronoUnit.MICROS);
            if (cutoff!=null) cutoff=cutoff.truncatedTo(ChronoUnit.MICROS);
            if (id == null || !ClickModel.SCHEMA.equals(schemaVersion) || windowStart == null || cutoff == null
                    || !windowStart.isBefore(cutoff) || Duration.between(windowStart, cutoff).compareTo(Duration.ofDays(90)) > 0
                    || sourceId == null || sourceId.isBlank() || sourceId.length() > 128 || sourceId.chars().anyMatch(Character::isISOControl)
                    || rows == null || rows.size() > 10_000 || rows.stream().anyMatch(r -> r == null))
                throw new IllegalArgumentException("Invalid snapshot metadata or row count (maximum 10000)");
            rows = rows.stream().sorted(Comparator.comparing(Counts::adId)).toList();
            if (rows.stream().map(Counts::adId).distinct().count() != rows.size())
                throw new IllegalArgumentException("Feature snapshot contains duplicate ad IDs");
        }
    }
    public record Snapshot(UUID id, Instant cutoff, Instant availableAt) { }
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    public FeatureStore(JdbcClient jdbc, JsonMapper json, Clock clock) { this.jdbc=jdbc; this.json=json; this.clock=clock; }

    @Transactional
    public boolean publish(Publication publication) {
        if (publication.cutoff().isAfter(clock.instant())) throw new IllegalArgumentException("Snapshot cutoff is in the future");
        String hash = ClickModel.sha256(json.writeValueAsString(publication));
        int inserted = jdbc.sql("""
                INSERT INTO feature_snapshots(id,schema_version,window_start,cutoff,available_at,source_id,content_hash)
                VALUES (:id,:schema,:start,:cutoff,:available,:source,:hash) ON CONFLICT (id) DO NOTHING
                """).param("id", publication.id()).param("schema", publication.schemaVersion())
                .param("start", publication.windowStart().atOffset(ZoneOffset.UTC))
                .param("cutoff", publication.cutoff().atOffset(ZoneOffset.UTC))
                .param("available", clock.instant().atOffset(ZoneOffset.UTC))
                .param("source", publication.sourceId()).param("hash", hash).update();
        String stored = jdbc.sql("SELECT content_hash FROM feature_snapshots WHERE id=:id")
                .param("id", publication.id()).query(String.class).single();
        if (!hash.equals(stored)) throw PlatformException.conflict("Snapshot ID already contains different data");
        if (inserted == 0) return false;
        if (!publication.rows().isEmpty()) {
            long existing = jdbc.sql("SELECT count(*) FROM ads WHERE id IN (:ids)")
                    .param("ids", publication.rows().stream().map(Counts::adId).toList()).query(Long.class).single();
            if (existing != publication.rows().size()) throw new IllegalArgumentException("Snapshot references unknown ads");
        }
        jdbc.sql("""
                INSERT INTO ad_features(snapshot_id,ad_id,impressions,clicked_impressions)
                SELECT :snapshot,x."adId",x.impressions,x."clickedImpressions"
                FROM jsonb_to_recordset(CAST(:rows AS jsonb))
                  AS x("adId" uuid,impressions bigint,"clickedImpressions" bigint)
                """).param("snapshot",publication.id()).param("rows",json.writeValueAsString(publication.rows())).update();
        return true;
    }

    public Optional<Snapshot> latest() {
        return jdbc.sql("""
                SELECT id,cutoff,available_at FROM feature_snapshots
                WHERE schema_version=:schema AND cutoff<=:now AND available_at<=:now
                ORDER BY cutoff DESC, available_at DESC, id DESC LIMIT 1
                """).param("schema", ClickModel.SCHEMA).param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query((rs,n) -> new Snapshot(rs.getObject(1,UUID.class), rs.getObject(2,OffsetDateTime.class).toInstant(),
                        rs.getObject(3,OffsetDateTime.class).toInstant())).optional();
    }

    public Map<UUID, Counts> counts(UUID snapshot, List<UUID> candidates) {
        if (candidates.isEmpty()) return Map.of();
        Map<UUID, Counts> result = new HashMap<>();
        jdbc.sql("SELECT ad_id,impressions,clicked_impressions FROM ad_features WHERE snapshot_id=:snapshot AND ad_id IN (:ids)")
                .param("snapshot", snapshot).param("ids", candidates)
                .query((rs,n) -> new Counts(rs.getObject(1,UUID.class),rs.getLong(2),rs.getLong(3)))
                .list().forEach(row -> result.put(row.adId(),row));
        return Map.copyOf(result);
    }
}
