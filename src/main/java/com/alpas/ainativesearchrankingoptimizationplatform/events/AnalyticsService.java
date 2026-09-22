package com.alpas.ainativesearchrankingoptimizationplatform.events;

import com.alpas.ainativesearchrankingoptimizationplatform.ml.ClickModel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
    public record TrainingRow(UUID impressionId, UUID requestId, UUID adId, Instant predictionTime,
                              Instant impressionTime, Instant labelAvailableAt, String schemaVersion,
                              double textRelevance, double smoothedCtr, double logImpressions, int label) { }
    public record Page(Instant asOf, List<TrainingRow> rows, UUID nextAfter) { }
    public record Ctr(UUID adId, long impressions, long clickedImpressions, double ctr) { }
    public record Quality(String modelVersion, long impressions, double logLoss, double brierScore, double ctr) { }
    private final JdbcClient jdbc;
    private final Clock clock;
    public AnalyticsService(JdbcClient jdbc, Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    public Page training(Instant asOf, UUID after, int limit) {
        validateCutoff(asOf);
        if (limit < 1 || limit > 5000) throw new IllegalArgumentException("Export page size must be 1-5000");
        List<TrainingRow> rows = jdbc.sql("""
                SELECT i.id,i.request_id,i.ad_id,r.created_at,i.occurred_at,
                       i.occurred_at+INTERVAL '25 hours' AS label_available_at,
                       s.text_relevance,s.smoothed_ctr,s.log_impressions,
                       CASE WHEN EXISTS (SELECT 1 FROM clicks c WHERE c.impression_id=i.id
                            AND c.occurred_at>=i.occurred_at AND c.occurred_at<i.occurred_at+INTERVAL '24 hours'
                            AND c.received_at<=:asOf) THEN 1 ELSE 0 END AS label
                FROM impressions i JOIN search_requests r ON r.id=i.request_id
                JOIN search_results s ON s.request_id=i.request_id AND s.ad_id=i.ad_id
                WHERE i.received_at<=:asOf AND i.occurred_at+INTERVAL '25 hours'<=:asOf
                  AND (CAST(:after AS uuid) IS NULL OR i.id>CAST(:after AS uuid))
                ORDER BY i.id LIMIT :limit
                """).param("asOf",asOf.atOffset(ZoneOffset.UTC)).param("after",after == null ? null : after.toString())
                .param("limit",limit+1).query((rs,n)->new TrainingRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,UUID.class),rs.getObject(4,OffsetDateTime.class).toInstant(),
                        rs.getObject(5,OffsetDateTime.class).toInstant(),rs.getObject(6,OffsetDateTime.class).toInstant(),
                        ClickModel.SCHEMA,rs.getDouble(7),rs.getDouble(8),rs.getDouble(9),rs.getInt(10))).list();
        boolean more = rows.size()>limit;
        List<TrainingRow> page = List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new Page(asOf,page,more ? page.get(page.size()-1).impressionId() : null);
    }

    public List<Ctr> ctr(Instant from, Instant to, Instant asOf) {
        validateCutoff(asOf);
        if (from == null || to == null || !from.isBefore(to) || to.isAfter(asOf)
                || Duration.between(from,to).compareTo(Duration.ofDays(31)) > 0)
            throw new IllegalArgumentException("CTR needs a nonempty range of at most 31 days ending by the cutoff");
        return jdbc.sql("""
                SELECT i.ad_id,count(*) AS impressions,count(*) FILTER (WHERE EXISTS (
                    SELECT 1 FROM clicks c WHERE c.impression_id=i.id AND c.received_at<=:asOf
                    AND c.occurred_at>=i.occurred_at AND c.occurred_at<i.occurred_at+INTERVAL '24 hours'
                )) AS clicked
                FROM impressions i WHERE i.occurred_at>=:from AND i.occurred_at<:to
                  AND i.received_at<=:asOf AND i.occurred_at+INTERVAL '25 hours'<=:asOf
                GROUP BY i.ad_id ORDER BY i.ad_id
                """).param("from",from.atOffset(ZoneOffset.UTC)).param("to",to.atOffset(ZoneOffset.UTC))
                .param("asOf",asOf.atOffset(ZoneOffset.UTC)).query((rs,n)->new Ctr(rs.getObject(1,UUID.class),
                        rs.getLong(2),rs.getLong(3),(double)rs.getLong(3)/rs.getLong(2))).list();
    }
    private void validateCutoff(Instant asOf) {
        if (asOf == null || asOf.isAfter(clock.instant())) throw new IllegalArgumentException("A reporting cutoff no later than now is required");
    }

    public List<Quality> quality(Instant from, Instant to, Instant asOf) {
        validateCutoff(asOf);
        if (from == null || to == null || !from.isBefore(to) || to.isAfter(asOf)
                || Duration.between(from,to).compareTo(Duration.ofDays(31))>0)
            throw new IllegalArgumentException("Quality report needs a nonempty range of at most 31 days");
        return jdbc.sql("""
                WITH predictions AS (
                    SELECT r.model_version, greatest(1e-15,least(1-1e-15,s.score)) AS p,
                        CASE WHEN EXISTS (SELECT 1 FROM clicks c WHERE c.impression_id=i.id
                            AND c.received_at<=:asOf AND c.occurred_at>=i.occurred_at
                            AND c.occurred_at<i.occurred_at+INTERVAL '24 hours') THEN 1.0 ELSE 0.0 END AS y
                    FROM impressions i JOIN search_requests r ON r.id=i.request_id
                    JOIN search_results s ON s.request_id=i.request_id AND s.ad_id=i.ad_id
                    WHERE r.policy='model' AND i.occurred_at>=:from AND i.occurred_at<:to
                      AND i.received_at<=:asOf AND i.occurred_at+INTERVAL '25 hours'<=:asOf
                )
                SELECT model_version,count(*),avg(-y*ln(p)-(1-y)*ln(1-p)),avg(power(p-y,2)),avg(y)
                FROM predictions GROUP BY model_version ORDER BY model_version
                """).param("from",from.atOffset(ZoneOffset.UTC)).param("to",to.atOffset(ZoneOffset.UTC))
                .param("asOf",asOf.atOffset(ZoneOffset.UTC))
                .query((rs,n)->new Quality(rs.getString(1),rs.getLong(2),rs.getDouble(3),rs.getDouble(4),rs.getDouble(5))).list();
    }
}
