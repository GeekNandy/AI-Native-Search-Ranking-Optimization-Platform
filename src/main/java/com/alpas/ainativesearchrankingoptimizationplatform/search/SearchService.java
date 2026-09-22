package com.alpas.ainativesearchrankingoptimizationplatform.search;

import com.alpas.ainativesearchrankingoptimizationplatform.experiments.ExperimentService;
import com.alpas.ainativesearchrankingoptimizationplatform.features.FeatureStore;
import com.alpas.ainativesearchrankingoptimizationplatform.ml.ClickModel;
import com.alpas.ainativesearchrankingoptimizationplatform.ml.ModelRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

@Service
public class SearchService {
    public record Request(String query, String userId, int limit, String experimentId) {
        public Request {
            if (query == null || query.isBlank() || query.codePointCount(0,query.length()) > 120
                    || query.chars().anyMatch(Character::isISOControl)
                    || userId == null || !userId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || limit < 1 || limit > 50)
                throw new IllegalArgumentException("Provide a query (1-120 characters), opaque user ID, and limit (1-50)");
            query = query.strip();
            if (experimentId != null) ClickModel.identifier(experimentId);
        }
    }
    public record Item(UUID adId, String title, String category, int position, double score) { }
    public record Response(UUID requestId, Instant createdAt, String policy, String modelVersion, UUID snapshotId,
                           String experimentId, String arm, String fallbackReason, List<Item> items) { }
    private record Candidate(UUID id, String title, String category, double relevance) { }
    private record Scored(Candidate candidate, double[] features, double score) { }
    private final JdbcClient jdbc;
    private final FeatureStore features;
    private final ModelRegistry models;
    private final ExperimentService experiments;
    private final Clock clock;
    private final MeterRegistry meters;

    public SearchService(JdbcClient jdbc, FeatureStore features, ModelRegistry models,
                         ExperimentService experiments, Clock clock, MeterRegistry meters) {
        this.jdbc=jdbc; this.features=features; this.models=models; this.experiments=experiments; this.clock=clock; this.meters=meters;
    }

    @Transactional
    public Response search(Request request) {
        Timer.Sample timer = Timer.start(meters);
        try { return execute(request); }
        finally { timer.stop(meters.timer("platform.search.duration")); }
    }

    private Response execute(Request request) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ExperimentService.Assignment assignment = request.experimentId() == null ? null
                : experiments.assign(request.experimentId(),request.userId(),now);
        String target;
        if (request.experimentId() != null) target = assignment == null ? null : assignment.modelVersion();
        else {
            ModelRegistry.Deployment deployment = models.deployment();
            target = deployment.modelVersion() != null && ClickModel.bucket("rollout",deployment.modelVersion(),request.userId())
                    < deployment.rolloutPercent()*100 ? deployment.modelVersion() : null;
        }
        List<Candidate> candidates = jdbc.sql("""
                SELECT id,title,category,
                  ts_rank_cd(to_tsvector('simple',title || ' ' || category),plainto_tsquery('simple',:query),32) AS relevance
                FROM ads WHERE to_tsvector('simple',title || ' ' || category) @@ plainto_tsquery('simple',:query)
                ORDER BY relevance DESC,id LIMIT 200
                """).param("query",request.query()).query((rs,n) -> new Candidate(rs.getObject(1,UUID.class),
                        rs.getString(2),rs.getString(3),rs.getDouble(4))).list();
        FeatureStore.Snapshot snapshot = features.latest(now).orElse(null);
        String fallback = target == null ? "baseline_selected" : null;
        if (request.experimentId()!=null && assignment==null) fallback="experiment_stopped";
        if (snapshot == null) fallback = target == null ? fallback : "missing_features";
        else {
            long age = Duration.between(snapshot.cutoff(),now).getSeconds();
            meters.summary("platform.features.age.seconds").record(age);
            if (age > Duration.ofHours(24).getSeconds()) {
                snapshot = null;
                fallback = "stale_features";
            }
        }
        Map<UUID,FeatureStore.Counts> counts = snapshot == null ? Map.of()
                : features.counts(snapshot.id(),candidates.stream().map(Candidate::id).toList());
        ClickModel model = null;
        if (target != null && snapshot != null) {
            try {
                model = models.find(target).orElse(null);
                if (model == null) fallback = "missing_model";
            } catch (IllegalArgumentException | JacksonException invalid) { fallback = "invalid_model"; }
        }
        List<Scored> scored;
        try { scored = score(candidates,counts,model); }
        catch (IllegalArgumentException invalid) {
            model = null; fallback = "invalid_score"; scored = score(candidates,counts,null);
        }
        String policy = model == null ? "baseline" : "model";
        if (model != null) fallback = null;
        String servedModel = model == null ? null : model.version();
        UUID requestId = UUID.randomUUID(), snapshotId = snapshot == null ? null : snapshot.id();
        String arm = assignment == null ? null : assignment.arm();
        String experimentId=assignment==null?null:assignment.experimentId();
        jdbc.sql("""
                INSERT INTO search_requests(id,user_id,query,created_at,policy,model_version,snapshot_id,experiment_id,arm,fallback_reason)
                VALUES (:id,:user,:query,:created,:policy,:model,:snapshot,:experiment,:arm,:fallback)
                """).param("id",requestId).param("user",request.userId()).param("query",request.query())
                .param("created",now.atOffset(ZoneOffset.UTC)).param("policy",policy).param("model",servedModel)
                .param("snapshot",snapshotId).param("experiment",experimentId).param("arm",arm).param("fallback",fallback).update();
        List<Item> items = new ArrayList<>();
        for (Scored row : scored.stream().limit(request.limit()).toList()) {
            int position = items.size()+1;
            jdbc.sql("""
                    INSERT INTO search_results(request_id,ad_id,position,score,text_relevance,smoothed_ctr,log_impressions)
                    VALUES (:request,:ad,:position,:score,:relevance,:ctr,:views)
                    """).param("request",requestId).param("ad",row.candidate().id()).param("position",position)
                    .param("score",row.score()).param("relevance",row.features()[0]).param("ctr",row.features()[1])
                    .param("views",row.features()[2]).update();
            items.add(new Item(row.candidate().id(),row.candidate().title(),row.candidate().category(),position,row.score()));
            meters.summary("platform.ranking.score","policy",policy).record(row.score());
        }
        meters.counter("platform.search.requests","policy",policy).increment();
        if (fallback != null) meters.counter("platform.ranking.fallback","reason",fallback).increment();
        long missing = candidates.stream().filter(c -> !counts.containsKey(c.id())).count();
        meters.counter("platform.features.missing").increment(missing);
        return new Response(requestId,now,policy,servedModel,snapshotId,experimentId,arm,fallback,List.copyOf(items));
    }

    private static List<Scored> score(List<Candidate> candidates, Map<UUID,FeatureStore.Counts> counts, ClickModel model) {
        return candidates.stream().map(candidate -> {
            FeatureStore.Counts count = counts.get(candidate.id());
            double[] vector = ClickModel.features(candidate.relevance(),count == null ? 0 : count.impressions(),
                    count == null ? 0 : count.clickedImpressions());
            return new Scored(candidate,vector,model == null ? vector[0]+0.25*vector[1] : model.predict(vector));
        }).sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s -> s.candidate().id())).toList();
    }
}
