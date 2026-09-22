package com.alpas.ainativesearchrankingoptimizationplatform.events;

import com.alpas.ainativesearchrankingoptimizationplatform.platform.PlatformException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TrainingExports {
    public record Request(UUID id, Instant asOf) {
        public Request {
            if(id==null || asOf==null) throw new IllegalArgumentException("Export ID and cutoff are required");
            asOf=asOf.truncatedTo(ChronoUnit.MICROS);
        }
    }
    public record Export(UUID id, Instant asOf, long rows, boolean created) { }
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    public TrainingExports(JdbcClient jdbc,JsonMapper json,Clock clock) { this.jdbc=jdbc;this.json=json;this.clock=clock; }

    @Transactional
    public Export create(Request request) {
        if(request.asOf().isAfter(clock.instant())) throw new IllegalArgumentException("Export cutoff cannot be in the future");
        int inserted=jdbc.sql("INSERT INTO training_exports(id,as_of) VALUES (:id,:asOf) ON CONFLICT(id) DO NOTHING")
                .param("id",request.id()).param("asOf",request.asOf().atOffset(ZoneOffset.UTC)).update();
        Instant stored=cutoff(request.id());
        if(!stored.equals(request.asOf())) throw PlatformException.conflict("Export ID already has a different cutoff");
        if(inserted==1) {
            // One INSERT SELECT observes one MVCC snapshot, even while new events commit. Pagination reads the immutable result.
            int rows=jdbc.sql("""
                    INSERT INTO training_examples(export_id,impression_id,payload)
                    SELECT :export,i.id,jsonb_build_object(
                        'impressionId',i.id,'requestId',i.request_id,'adId',i.ad_id,'predictionTime',r.created_at,
                        'impressionTime',i.occurred_at,'labelAvailableAt',i.occurred_at+INTERVAL '25 hours',
                        'schemaVersion','click-v1','textRelevance',s.text_relevance,'smoothedCtr',s.smoothed_ctr,
                        'logImpressions',s.log_impressions,'label',CASE WHEN EXISTS (
                            SELECT 1 FROM clicks c WHERE c.impression_id=i.id AND c.received_at<=:asOf
                              AND c.occurred_at>=i.occurred_at AND c.occurred_at<i.occurred_at+INTERVAL '24 hours'
                        ) THEN 1 ELSE 0 END)
                    FROM impressions i JOIN search_requests r ON r.id=i.request_id
                    JOIN search_results s ON s.request_id=i.request_id AND s.ad_id=i.ad_id
                    WHERE i.received_at<=:asOf AND i.occurred_at+INTERVAL '25 hours'<=:asOf
                    ORDER BY i.id LIMIT 100001
                    """).param("export",request.id()).param("asOf",request.asOf().atOffset(ZoneOffset.UTC)).update();
            if(rows>100000) throw new PlatformException(422,"Export exceeds the supported 100000-example bound");
        }
        long rows=jdbc.sql("SELECT count(*) FROM training_examples WHERE export_id=:id").param("id",request.id()).query(Long.class).single();
        return new Export(request.id(),stored,rows,inserted==1);
    }

    public AnalyticsService.Page page(UUID id,UUID after,int limit) {
        if(limit<1 || limit>5000) throw new IllegalArgumentException("Export page size must be 1-5000");
        Instant asOf=cutoff(id);
        List<AnalyticsService.TrainingRow> rows=jdbc.sql("""
                SELECT payload::text FROM training_examples WHERE export_id=:id
                  AND (CAST(:after AS uuid) IS NULL OR impression_id>CAST(:after AS uuid))
                ORDER BY impression_id LIMIT :limit
                """).param("id",id).param("after",after==null?null:after.toString()).param("limit",limit+1)
                .query(String.class).list().stream().map(value->json.readValue(value,AnalyticsService.TrainingRow.class)).toList();
        List<AnalyticsService.TrainingRow> result=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new AnalyticsService.Page(asOf,result,rows.size()>limit?result.get(result.size()-1).impressionId():null);
    }
    private Instant cutoff(UUID id) {
        return jdbc.sql("SELECT as_of FROM training_exports WHERE id=:id").param("id",id)
                .query((rs,n)->rs.getObject(1,OffsetDateTime.class).toInstant()).optional()
                .orElseThrow(()->PlatformException.missing("Training export not found"));
    }
}
