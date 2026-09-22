package com.alpas.ainativesearchrankingoptimizationplatform.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.alpas.ainativesearchrankingoptimizationplatform.ml.ClickModel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Import(RankingPlatformIT.TimeConfiguration.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="platform.admin-token=integration-only-token-with-32-characters")
class RankingPlatformIT {
    private static final String TOKEN="integration-only-token-with-32-characters";
    private static final Instant START=Instant.parse("2026-09-01T12:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
    }
    public static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(START);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        @Override public Instant instant() { return value.get(); }
        void advance(Duration duration) { value.updateAndGet(i->i.plus(duration)); }
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;
    @Autowired JsonMapper json;
    private final HttpClient http=HttpClient.newHttpClient();
    private String firstAd,secondAd;

    @BeforeEach void reset() throws Exception {
        jdbc.sql("TRUNCATE training_examples,training_exports,clicks,impressions,search_results,search_requests,assignments,experiments,deployment_history,ranking_deployment,models,ad_features,feature_snapshots,ads CASCADE").update();
        jdbc.sql("INSERT INTO ranking_deployment(singleton) VALUES (TRUE)").update();
        clock.value.set(START);
        firstAd=ok("POST","/api/v1/ads",Map.of("title","Camera Alpha","category","Cameras"),201).path("id").asText();
        secondAd=ok("POST","/api/v1/ads",Map.of("title","Camera Beta","category","Cameras"),201).path("id").asText();
    }

    @Test void recordsActualExposureAndProducesMatureDeduplicatedLabels() throws Exception {
        JsonNode search=search("user-one",null);
        assertEquals("baseline",search.path("policy").asText());
        assertEquals(0,export(5000,null).path("rows").size(),"Returned results are not impressions");
        String request=search.path("requestId").asText();
        String impression=UUID.randomUUID().toString();
        Map<String,Object> first=Map.of("id",impression,"requestId",request,"adId",firstAd,"occurredAt",clock.instant().toString());
        ok("POST","/api/v1/events/impressions",first,201);
        ok("POST","/api/v1/events/impressions",first,200);
        ok("POST","/api/v1/events/impressions",Map.of("id",impression,"requestId",request,"adId",secondAd,"occurredAt",clock.instant().toString()),409);
        ok("POST","/api/v1/events/impressions",Map.of("id",UUID.randomUUID().toString(),"requestId",request,"adId",firstAd,"occurredAt",clock.instant().toString()),409);
        ok("POST","/api/v1/events/impressions",Map.of("id",UUID.randomUUID().toString(),"requestId",request,"adId",secondAd,"occurredAt",clock.instant().toString()),201);
        Map<String,Object> click=Map.of("id",UUID.randomUUID().toString(),"impressionId",impression,"occurredAt",clock.instant().toString());
        ok("POST","/api/v1/events/clicks",click,201); ok("POST","/api/v1/events/clicks",click,200);
        ok("POST","/api/v1/events/clicks",Map.of("id",UUID.randomUUID().toString(),"impressionId",impression,"occurredAt",clock.instant().toString()),201);
        clock.advance(Duration.ofHours(24));
        assertEquals(0,export(5000,null).path("rows").size());
        clock.advance(Duration.ofHours(2));
        JsonNode page=export(1,null);
        assertEquals(1,page.path("rows").size()); assertFalse(page.path("nextAfter").isNull());
        JsonNode page2=export(1,page.path("nextAfter").asText());
        assertEquals(1,page2.path("rows").size()); assertTrue(page2.path("nextAfter").isNull());
        assertEquals(1,page.path("rows").get(0).path("label").asInt()+page2.path("rows").get(0).path("label").asInt());
        ok("POST","/api/v1/events/impressions",first,200); // Identical retries remain valid after the lateness window.
        JsonNode ctr=ok("GET","/api/v1/admin/metrics/ctr?from="+START+"&to="+START.plusSeconds(1)+"&asOf="+clock.instant(),null,200);
        assertEquals(2,ctr.size());
        assertEquals(1L,ctr.get(0).path("clickedImpressions").asLong()+ctr.get(1).path("clickedImpressions").asLong());
        assertEquals(2L,ctr.get(0).path("impressions").asLong()+ctr.get(1).path("impressions").asLong());
        String exportId=UUID.randomUUID().toString();
        Map<String,Object> exportRequest=Map.of("id",exportId,"asOf",clock.instant().toString());
        assertEquals(2,ok("POST","/api/v1/admin/training-exports",exportRequest,201).path("rows").asInt());
        ok("POST","/api/v1/admin/training-exports",exportRequest,200);
        ok("POST","/api/v1/admin/training-exports",Map.of("id",exportId,"asOf",START.toString()),409);
        JsonNode frozen=ok("GET","/api/v1/admin/training-exports/"+exportId+"?limit=1",null,200);
        assertEquals(1,frozen.path("rows").size());
        assertEquals(1,ok("GET","/api/v1/admin/training-exports/"+exportId+"?after="+frozen.path("nextAfter").asText(),null,200).path("rows").size());
    }

    @Test void validatesTimeAndAttributionRatherThanAcceptingArbitraryEvents() throws Exception {
        JsonNode search=search("user",null); String request=search.path("requestId").asText();
        String id=UUID.randomUUID().toString();
        ok("POST","/api/v1/events/clicks",Map.of("id",UUID.randomUUID().toString(),"impressionId",id,"occurredAt",START.toString()),409);
        ok("POST","/api/v1/events/impressions",Map.of("id",id,"requestId",request,"adId",firstAd,"occurredAt",START.plusSeconds(1).toString()),400);
        ok("POST","/api/v1/events/impressions",Map.of("id",id,"requestId",request,"adId",firstAd,"occurredAt",START.toString()),201);
        clock.advance(Duration.ofHours(24));
        ok("POST","/api/v1/events/clicks",Map.of("id",UUID.randomUUID().toString(),"impressionId",id,"occurredAt",clock.instant().toString()),400);
        ok("POST","/api/v1/events/clicks",Map.of("id",UUID.randomUUID().toString(),"impressionId",id,"occurredAt",START.toString()),400);
        ok("GET","/api/v1/admin/training-data?asOf="+clock.instant().plusSeconds(1),null,400);
    }

    @Test void publishesImmutableSnapshotsAtomicallyAndFallsBackWhenStale() throws Exception {
        String id=UUID.randomUUID().toString(); Map<String,Object> snapshot=snapshot(id,firstAd);
        ok("POST","/api/v1/admin/features/snapshots",snapshot,201);
        ok("POST","/api/v1/admin/features/snapshots",snapshot,200);
        ok("POST","/api/v1/admin/features/snapshots",snapshot(id,secondAd),409);
        String missing=UUID.randomUUID().toString();
        ok("POST","/api/v1/admin/features/snapshots",snapshot(missing,UUID.randomUUID().toString()),400);
        assertEquals(0L,jdbc.sql("SELECT count(*) FROM feature_snapshots WHERE id=CAST(:id AS uuid)").param("id",missing).query(Long.class).single());
        register("model-a",0.4);
        deploy(0,"model-a",100,200);
        JsonNode ranked=search("user",null);
        assertEquals("model",ranked.path("policy").asText()); assertEquals(id,ranked.path("snapshotId").asText());
        assertTrue(ranked.path("items").get(0).path("score").asDouble()<1);
        clock.advance(Duration.ofHours(25));
        JsonNode fallback=search("user",null);
        assertEquals("baseline",fallback.path("policy").asText());
        assertEquals("stale_features",fallback.path("fallbackReason").asText());
    }

    @Test void preventsLostUpdatesAndSupportsRollbackToAnImmutableModel() throws Exception {
        ok("POST","/api/v1/admin/features/snapshots",snapshot(UUID.randomUUID().toString(),firstAd),201);
        register("model-a",0.4); register("model-b",0.3);
        var a=http.sendAsync(request("PUT","/api/v1/admin/deployment",Map.of("revision",0,"modelVersion","model-a","rolloutPercent",100),true),HttpResponse.BodyHandlers.ofString());
        var b=http.sendAsync(request("PUT","/api/v1/admin/deployment",Map.of("revision",0,"modelVersion","model-b","rolloutPercent",100),true),HttpResponse.BodyHandlers.ofString());
        assertEquals(Set.of(200,409),Set.of(a.join().statusCode(),b.join().statusCode()));
        deploy(1,"model-b",100,200); assertEquals("model-b",search("user",null).path("modelVersion").asText());
        deploy(2,"model-a",100,200); assertEquals("model-a",search("user",null).path("modelVersion").asText());
        deploy(2,"model-b",100,409);
        assertEquals(3,ok("GET","/api/v1/admin/deployment/history",null,200).size());
        register("worse-model",0.8); deploy(3,"worse-model",100,422);
    }

    @Test void rejectsSchemaMismatchAndConflictingModelContent() throws Exception {
        ClickModel model=model("model-a",0.4);
        ok("POST","/api/v1/admin/models",model,201); ok("POST","/api/v1/admin/models",model,200);
        ok("POST","/api/v1/admin/models",model("model-a",0.3),409);
        String altered=json.writeValueAsString(model).replace("click-v1","unknown-schema");
        HttpResponse<String> response=http.send(rawRequest("POST","/api/v1/admin/models",altered,true),HttpResponse.BodyHandlers.ofString());
        assertEquals(400,response.statusCode(),response.body());
        String missingEvidence=json.writeValueAsString(model).replace("\"validationLogLoss\":0.4,","");
        assertEquals(400,http.send(rawRequest("POST","/api/v1/admin/models",missingEvidence,true),HttpResponse.BodyHandlers.ofString()).statusCode());
        deploy(0,"does-not-exist",100,404);
    }

    @Test void experimentUsesStableAssignmentsAndCountsUnexposedNonConverters() throws Exception {
        register("model-a",0.4);
        ok("POST","/api/v1/admin/features/snapshots",snapshot(UUID.randomUUID().toString(),firstAd),201);
        Map<String,Object> experiment=Map.of("id","ranking-test","salt","fixed-salt","modelVersion","model-a","treatmentPercent",50);
        ok("POST","/api/v1/admin/experiments",experiment,201); ok("POST","/api/v1/admin/experiments",experiment,200);
        String control=userFor(false),treatment=userFor(true);
        JsonNode c=search(control,"ranking-test"),t=search(treatment,"ranking-test");
        assertEquals("control",c.path("arm").asText()); assertEquals("treatment",t.path("arm").asText());
        assertEquals(c.path("arm"),search(control,"ranking-test").path("arm"));
        String impression=UUID.randomUUID().toString();
        ok("POST","/api/v1/events/impressions",Map.of("id",impression,"requestId",t.path("requestId").asText(),"adId",firstAd,"occurredAt",START.toString()),201);
        ok("POST","/api/v1/events/clicks",Map.of("id",UUID.randomUUID().toString(),"impressionId",impression,"occurredAt",START.toString()),201);
        clock.advance(Duration.ofHours(26));
        JsonNode report=ok("GET","/api/v1/admin/experiments/ranking-test/report?asOf="+clock.instant(),null,200).path("comparison");
        assertEquals(1,report.path("control").path("users").asInt()); assertEquals(0,report.path("control").path("convertedUsers").asInt());
        assertEquals(1,report.path("treatment").path("convertedUsers").asInt()); assertTrue(report.path("smallSample").asBoolean());
        JsonNode quality=ok("GET","/api/v1/admin/metrics/model-quality?from="+START+"&to="+START.plusSeconds(1)+"&asOf="+clock.instant(),null,200);
        assertEquals(1,quality.size()); assertEquals("model-a",quality.get(0).path("modelVersion").asText());
        assertEquals(1,quality.get(0).path("impressions").asInt());
        ok("POST","/api/v1/admin/experiments/ranking-test/stop",null,200);
        JsonNode stopped=search("new-user","ranking-test");
        assertEquals("baseline",stopped.path("policy").asText()); assertTrue(stopped.path("experimentId").isNull());
        assertEquals(2L,jdbc.sql("SELECT count(*) FROM assignments").query(Long.class).single());
    }

    @Test void administrativeEndpointsAndMetricsRequireTheToken() throws Exception {
        for (String path:List.of("/api/v1/admin/models","/api/v1/admin/deployment","/actuator/prometheus")) {
            HttpResponse<String> denied=http.send(request("GET",path,null,false),HttpResponse.BodyHandlers.ofString());
            assertEquals(403,denied.statusCode(),path+": "+denied.body());
        }
        search("metrics-user",null);
        HttpResponse<String> metrics=http.send(request("GET","/actuator/prometheus",null,true),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,metrics.statusCode()); assertTrue(metrics.body().contains("platform_search_requests_total"));
    }

    @Test void candidateTiesAreStableAndSearchInputIsBounded() throws Exception {
        assertEquals(search("user",null).path("items"),search("user",null).path("items"));
        ok("POST","/api/v1/search",Map.of("query","camera","userId","user","limit",51),400);
        ok("POST","/api/v1/search",Map.of("query"," ","userId","user","limit",10),400);
        ok("POST","/api/v1/search",Map.of("query","camera","userId","user@example.com","limit",10),400);
    }

    private String userFor(boolean treatment) {
        for (int i=0;i<1000;i++) if ((ClickModel.bucket("ranking-test","fixed-salt","u-"+i)<5000)==treatment) return "u-"+i;
        throw new AssertionError("No matching assignment bucket");
    }
    private Map<String,Object> snapshot(String id,String ad) {
        return Map.of("id",id,"schemaVersion",ClickModel.SCHEMA,"windowStart",START.minus(Duration.ofDays(30)).toString(),
                "cutoff",START.toString(),"sourceId","integration-fixture","rows",List.of(Map.of("adId",ad,"impressions",100,"clickedImpressions",30)));
    }
    private ClickModel model(String version,double validationLoss) {
        return new ClickModel(version,ClickModel.SCHEMA,ClickModel.FEATURES,List.of(0.0,0.0,0.0),List.of(1.0,1.0,1.0),
                List.of(1.0,1.0,0.1),-1,START.minus(Duration.ofDays(10)),START.minus(Duration.ofDays(5)),START.minusSeconds(1),
                100,50,50,validationLoss,0.6,0.4,0.6,0.2,"integration-fixture");
    }
    private void register(String version,double loss) throws Exception { ok("POST","/api/v1/admin/models",model(version,loss),201); }
    private void deploy(long revision,String model,int percent,int status) throws Exception {
        ok("PUT","/api/v1/admin/deployment",Map.of("revision",revision,"modelVersion",model,"rolloutPercent",percent),status);
    }
    private JsonNode search(String user,String experiment) throws Exception {
        var payload=new java.util.HashMap<String,Object>(Map.of("query","camera","userId",user,"limit",10));
        if(experiment!=null)payload.put("experimentId",experiment);
        return ok("POST","/api/v1/search",payload,200);
    }
    private JsonNode export(int limit,String after) throws Exception {
        return ok("GET","/api/v1/admin/training-data?asOf="+clock.instant()+"&limit="+limit+(after==null?"":"&after="+after),null,200);
    }
    private JsonNode ok(String method,String path,Object body,int status) throws Exception {
        HttpResponse<String> response=http.send(request(method,path,body,true),HttpResponse.BodyHandlers.ofString());
        assertEquals(status,response.statusCode(),response.body()); return json.readTree(response.body());
    }
    private HttpRequest request(String method,String path,Object body,boolean admin) {
        return rawRequest(method,path,body==null?null:json.writeValueAsString(body),admin);
    }
    private HttpRequest rawRequest(String method,String path,String body,boolean admin) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(admin)builder.header("X-Admin-Token",TOKEN);
        if(body!=null)builder.header("Content-Type","application/json");
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
