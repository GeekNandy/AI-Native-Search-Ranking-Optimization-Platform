package com.alpas.ainativesearchrankingoptimizationplatform.platform;

import com.alpas.ainativesearchrankingoptimizationplatform.events.AnalyticsService;
import com.alpas.ainativesearchrankingoptimizationplatform.events.EventService;
import com.alpas.ainativesearchrankingoptimizationplatform.events.TrainingExports;
import com.alpas.ainativesearchrankingoptimizationplatform.experiments.ExperimentService;
import com.alpas.ainativesearchrankingoptimizationplatform.features.FeatureStore;
import com.alpas.ainativesearchrankingoptimizationplatform.ml.ClickModel;
import com.alpas.ainativesearchrankingoptimizationplatform.ml.ModelRegistry;
import com.alpas.ainativesearchrankingoptimizationplatform.search.SearchService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
class PlatformController {
    private final SearchService search;
    private final EventService events;
    private final AnalyticsService analytics;
    private final FeatureStore features;
    private final ModelRegistry models;
    private final ExperimentService experiments;
    private final TrainingExports exports;
    private final JsonMapper json;
    PlatformController(SearchService search, EventService events, AnalyticsService analytics,
                       FeatureStore features, ModelRegistry models, ExperimentService experiments,TrainingExports exports,JsonMapper json) {
        this.search=search; this.events=events; this.analytics=analytics; this.features=features; this.models=models; this.experiments=experiments;
        this.exports=exports;
        this.json=json;
    }

    @PostMapping("/search") SearchService.Response search(@RequestBody SearchService.Request request) { return search.search(request); }
    @PostMapping("/events/impressions") ResponseEntity<EventService.Receipt> impression(@RequestBody EventService.Impression event) {
        EventService.Receipt receipt = events.impression(event); return ResponseEntity.status(receipt.created()?201:200).body(receipt);
    }
    @PostMapping("/events/clicks") ResponseEntity<EventService.Receipt> click(@RequestBody EventService.Click event) {
        EventService.Receipt receipt = events.click(event); return ResponseEntity.status(receipt.created()?201:200).body(receipt);
    }
    @GetMapping("/admin/metrics/ctr") List<AnalyticsService.Ctr> ctr(@RequestParam Instant from, @RequestParam Instant to, @RequestParam Instant asOf) {
        return analytics.ctr(from,to,asOf);
    }
    @GetMapping("/admin/training-data") AnalyticsService.Page training(@RequestParam Instant asOf,
            @RequestParam(required=false) UUID after, @RequestParam(defaultValue="1000") int limit) { return analytics.training(asOf,after,limit); }
    @GetMapping("/admin/metrics/model-quality") List<AnalyticsService.Quality> quality(@RequestParam Instant from,@RequestParam Instant to,@RequestParam Instant asOf) {
        return analytics.quality(from,to,asOf);
    }
    @PostMapping("/admin/training-exports") ResponseEntity<TrainingExports.Export> createExport(@RequestBody TrainingExports.Request request) {
        TrainingExports.Export result=exports.create(request); return ResponseEntity.status(result.created()?201:200).body(result);
    }
    @GetMapping("/admin/training-exports/{id}") AnalyticsService.Page exportedRows(@PathVariable UUID id,
            @RequestParam(required=false) UUID after,@RequestParam(defaultValue="1000") int limit) { return exports.page(id,after,limit); }
    @PostMapping("/admin/features/snapshots") ResponseEntity<Map<String,Object>> publish(@RequestBody FeatureStore.Publication publication) {
        boolean created = features.publish(publication); return ResponseEntity.status(created?201:200).body(Map.of("id",publication.id(),"created",created));
    }
    @GetMapping("/admin/features/latest") FeatureStore.Snapshot latest() {
        return features.latest().orElseThrow(()->PlatformException.missing("No feature snapshot published"));
    }
    @PostMapping("/admin/models") ResponseEntity<Map<String,Object>> register(@RequestBody JsonNode artifact) {
        // Primitive Java defaults must not turn omitted evaluation evidence into apparently perfect zero loss.
        if (!artifact.isObject()) throw new IllegalArgumentException("Model artifact must be an object");
        for (var component : ClickModel.class.getRecordComponents())
            if (!artifact.hasNonNull(component.getName())) throw new IllegalArgumentException("Model field is required: "+component.getName());
        ClickModel model;
        try { model=json.readValue(artifact.toString(),ClickModel.class); }
        catch (JacksonException invalid) { throw new IllegalArgumentException("Invalid model artifact or feature schema"); }
        boolean created=models.register(model); return ResponseEntity.status(created?201:200).body(Map.of("version",model.version(),"created",created,"promotionEligible",model.promotionEligible()));
    }
    @GetMapping("/admin/models") List<String> versions() { return models.versions(); }
    @GetMapping("/admin/models/{version}") ClickModel model(@PathVariable String version) {
        return models.find(ClickModel.identifier(version)).orElseThrow(()->PlatformException.missing("Model not found"));
    }
    @GetMapping("/admin/deployment") ModelRegistry.Deployment deployment() { return models.deployment(); }
    @PutMapping("/admin/deployment") ModelRegistry.Deployment deploy(@RequestBody ModelRegistry.Deployment deployment) { return models.deploy(deployment); }
    @GetMapping("/admin/deployment/history") List<ModelRegistry.Deployment> history() { return models.history(); }
    @PostMapping("/admin/experiments") ResponseEntity<Map<String,Object>> experiment(@RequestBody ExperimentService.Experiment experiment) {
        boolean created=experiments.create(experiment); return ResponseEntity.status(created?201:200).body(Map.of("id",experiment.id(),"created",created));
    }
    @GetMapping("/admin/experiments/{id}/report") ExperimentService.Report report(@PathVariable String id,@RequestParam Instant asOf) {
        return experiments.report(id,asOf);
    }
    @PostMapping("/admin/experiments/{id}/stop") Map<String,Object> stop(@PathVariable String id) {
        return Map.of("id",id,"stopped",experiments.stop(id));
    }
}
