package com.alpas.ranking.analytics;

import static org.apache.spark.sql.functions.*;
import static org.apache.spark.ml.functions.vector_to_array;

import com.alpas.ainativesearchrankingoptimizationplatform.ml.ClickModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.apache.spark.ml.Pipeline;
import org.apache.spark.ml.PipelineModel;
import org.apache.spark.ml.PipelineStage;
import org.apache.spark.ml.classification.LogisticRegression;
import org.apache.spark.ml.classification.LogisticRegressionModel;
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator;
import org.apache.spark.ml.feature.StandardScaler;
import org.apache.spark.ml.feature.StandardScalerModel;
import org.apache.spark.ml.feature.VectorAssembler;
import org.apache.spark.ml.linalg.Vector;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;

/** Fits offline; exports the same pure-Java artifact used by the online scorer. */
public final class RankingPipeline {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final StructType INPUT = new StructType()
            .add("impressionId",DataTypes.StringType).add("requestId",DataTypes.StringType).add("adId",DataTypes.StringType)
            .add("predictionTime",DataTypes.TimestampType).add("impressionTime",DataTypes.TimestampType)
            .add("labelAvailableAt",DataTypes.TimestampType).add("schemaVersion",DataTypes.StringType)
            .add("textRelevance",DataTypes.DoubleType).add("smoothedCtr",DataTypes.DoubleType)
            .add("logImpressions",DataTypes.DoubleType).add("label",DataTypes.DoubleType);

    public static void main(String[] args) throws Exception {
        Map<String,String> options = options(args);
        Path output = Path.of(required(options,"output")).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists; use a new immutable run directory");
        Files.createDirectories(output.getParent());
        Path stage = Files.createTempDirectory(output.getParent(),".ranking-run-");
        Instant asOf = Instant.parse(required(options,"as-of"));
        boolean synthetic = options.containsKey("synthetic");
        Instant trainEnd = Instant.parse(options.getOrDefault("train-end",asOf.minus(Duration.ofDays(8)).toString()));
        Instant validationEnd = Instant.parse(options.getOrDefault("validation-end",asOf.minus(Duration.ofDays(4)).toString()));
        Instant windowStart = Instant.parse(options.getOrDefault("window-start",asOf.minus(Duration.ofDays(30)).toString()));
        if (!trainEnd.isBefore(validationEnd) || !validationEnd.isBefore(asOf) || !windowStart.isBefore(asOf)
                || Duration.between(windowStart,asOf).compareTo(Duration.ofDays(90))>0)
            throw new IllegalArgumentException("Invalid temporal boundaries");
        String version = ClickModel.identifier(required(options,"version"));
        UUID snapshotId = UUID.fromString(required(options,"snapshot-id"));
        Path input;
        if (synthetic) {
            input = stage.resolve("synthetic-training.jsonl");
            synthetic(input,options.containsKey("catalog") ? Path.of(options.get("catalog")) : null,asOf);
        } else input = Path.of(required(options,"input")).toAbsolutePath();
        String sourceId = (synthetic ? "synthetic:" : "sha256:")+digest(input);

        SparkSession spark = SparkSession.builder().appName("Search ranking training and features")
                .config("spark.sql.session.timeZone","UTC").config("spark.sql.shuffle.partitions","4").getOrCreate();
        spark.sparkContext().setLogLevel("WARN");
        try {
            Dataset<Row> data = spark.read().schema(INPUT).option("mode","FAILFAST").json(input.toString()).cache();
            validate(data,asOf);
            data.write().mode(SaveMode.ErrorIfExists).parquet(stage.resolve("canonical.parquet").toString());
            publishFeatures(data,stage,snapshotId,sourceId,windowStart,asOf);

            // Purge labels whose observation window crosses a split boundary. Never fit transforms on held-out data.
            Dataset<Row> train = data.filter(col("predictionTime").lt(ts(trainEnd)).and(col("labelAvailableAt").leq(ts(trainEnd)))).cache();
            Dataset<Row> validation = data.filter(col("predictionTime").geq(ts(trainEnd)).and(col("predictionTime").lt(ts(validationEnd)))
                    .and(col("labelAvailableAt").leq(ts(validationEnd)))).cache();
            Dataset<Row> test = data.filter(col("predictionTime").geq(ts(validationEnd)).and(col("labelAvailableAt").leq(ts(asOf)))).cache();
            long trainRows = validateSplit(train,"training"), validationRows = validateSplit(validation,"validation"), testRows = validateSplit(test,"test");
            double prior = train.agg(avg("label")).first().getDouble(0);
            double baselineValidation = metrics(validation.withColumn("p",lit(prior))).get("logLoss");
            double baselineTest = metrics(test.withColumn("p",lit(prior))).get("logLoss");
            PipelineModel best = null;
            double bestLoss = Double.POSITIVE_INFINITY, chosenRegularization = 0;
            List<Map<String,Double>> trials = new ArrayList<>();
            for (double reg : new double[]{0.01,0.1}) {
                Pipeline pipeline = new Pipeline().setStages(new PipelineStage[]{
                        new VectorAssembler().setInputCols(ClickModel.FEATURES.toArray(String[]::new)).setOutputCol("rawFeatures"),
                        new StandardScaler().setInputCol("rawFeatures").setOutputCol("features").setWithMean(true).setWithStd(true),
                        new LogisticRegression().setFeaturesCol("features").setLabelCol("label").setStandardization(false)
                                .setRegParam(reg).setMaxIter(100).setTol(1e-8)
                });
                PipelineModel fitted = pipeline.fit(train);
                double loss = metrics(predictions(fitted,validation)).get("logLoss");
                trials.add(Map.of("regularization",reg,"validationLogLoss",loss));
                if (loss < bestLoss) { best=fitted; bestLoss=loss; chosenRegularization=reg; }
            }
            if (best == null) throw new IllegalStateException("No finite model fit");
            Dataset<Row> scoredTest = predictions(best,test).cache();
            Map<String,Double> testMetrics = metrics(scoredTest);
            StandardScalerModel scaler = (StandardScalerModel)best.stages()[1];
            LogisticRegressionModel regression = (LogisticRegressionModel)best.stages()[2];
            double[] std = scaler.std().toArray(), weights = regression.coefficients().toArray();
            for (int i=0;i<std.length;i++) {
                // Spark maps zero-variance dimensions to zero, including at inference time.
                if (std[i]<1e-12) { std[i]=1; weights[i]=0; }
            }
            ClickModel model = new ClickModel(version,ClickModel.SCHEMA,ClickModel.FEATURES,
                    doubles(scaler.mean().toArray()),doubles(std),doubles(weights),regression.intercept(),
                    trainEnd,validationEnd,asOf,trainRows,validationRows,testRows,bestLoss,baselineValidation,
                    testMetrics.get("logLoss"),baselineTest,testMetrics.get("brier"),sourceId);
            double maxError = verifyServingParity(model,scoredTest);
            if (synthetic && (!model.promotionEligible() || model.testLogLoss()>=baselineTest))
                throw new IllegalStateException("Synthetic known-signal model failed to beat the constant-prior baseline");
            best.write().save(stage.resolve("spark-model").toString());
            JSON.writerWithDefaultPrettyPrinter().writeValue(stage.resolve("model.json").toFile(),model);
            Map<String,Object> report = new LinkedHashMap<>();
            report.put("sourceId",sourceId); report.put("synthetic",synthetic); report.put("featureNames",ClickModel.FEATURES);
            report.put("trainEnd",trainEnd); report.put("validationEnd",validationEnd); report.put("asOf",asOf);
            report.put("trainingRows",trainRows); report.put("validationRows",validationRows); report.put("testRows",testRows);
            report.put("purgedRows",data.count()-trainRows-validationRows-testRows);
            report.put("trials",trials); report.put("selectedRegularization",chosenRegularization);
            report.put("test",testMetrics); report.put("baselineValidationLogLoss",baselineValidation);
            report.put("baselineTestLogLoss",baselineTest); report.put("maxServingProbabilityError",maxError);
            report.put("testAuc",new BinaryClassificationEvaluator().setLabelCol("label").evaluate(scoredTest));
            report.put("modelNdcgAt10",ndcg(scoredTest,"p"));
            report.put("baselineNdcgAt10",ndcg(scoredTest.withColumn("baselineScore",col("textRelevance").plus(col("smoothedCtr").multiply(0.25))),"baselineScore"));
            report.put("calibration",scoredTest.withColumn("bin",least(lit(9),floor(col("p").multiply(10))))
                    .groupBy("bin").agg(count(lit(1)).alias("n"),avg("p").alias("meanPrediction"),avg("label").alias("observedRate"))
                    .orderBy("bin").toJSON().collectAsList().stream().map(s -> {
                        try { return JSON.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
                    }).toList());
            report.put("limitations",List.of("Observed clicks include exposure and position bias","Offline metrics do not establish causal lift",
                    "NDCG uses only observed impression groups with at least one click","Synthetic data is a pipeline check, not a quality benchmark"));
            JSON.writerWithDefaultPrettyPrinter().writeValue(stage.resolve("evaluation.json").toFile(),report);
            JSON.writeValue(stage.resolve("complete.json").toFile(),Map.of("version",version,"sourceId",sourceId,
                    "modelSha256",digest(stage.resolve("model.json")),"snapshotSha256",digest(stage.resolve("features.json"))));
            // This contract is for a local filesystem. Publication to the service is a separate authenticated transaction.
            Files.move(stage,output,StandardCopyOption.ATOMIC_MOVE);
            System.out.println("Completed immutable run: "+output);
            System.out.println("Serving parity max error="+maxError+", test log loss="+model.testLogLoss()+", baseline="+baselineTest);
        } finally { spark.stop(); }
    }

    private static void validate(Dataset<Row> data, Instant asOf) {
        long count = data.count();
        if (count == 0) throw new IllegalArgumentException("No mature examples to train");
        Column invalid = lit(false);
        for (String name : INPUT.fieldNames()) invalid = invalid.or(col(name).isNull());
        for (String name : new String[]{"impressionId","requestId","adId"})
            invalid=invalid.or(not(col(name).rlike("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")));
        invalid=invalid.or(not(col("schemaVersion").equalTo(ClickModel.SCHEMA)))
                .or(not(col("label").isin(0.0,1.0)))
                .or(col("predictionTime").gt(col("impressionTime")))
                .or(col("labelAvailableAt").lt(expr("impressionTime + INTERVAL 25 HOURS")))
                .or(col("labelAvailableAt").gt(ts(asOf)));
        for (String feature : ClickModel.FEATURES)
            invalid=invalid.or(isnan(col(feature))).or(col(feature).lt(0)).or(col(feature).gt(feature.equals("logImpressions")?Math.log1p(1e12):1));
        if (data.filter(invalid).limit(1).count()>0) throw new IllegalArgumentException("Invalid, future, immature or incompatible training row");
        if (data.select("impressionId").distinct().count()!=count) throw new IllegalArgumentException("Duplicate impression IDs in canonical export");
        if (data.groupBy("requestId").agg(countDistinct("predictionTime").alias("times")).filter(col("times").gt(1)).limit(1).count()>0)
            throw new IllegalArgumentException("Request prediction times are inconsistent");
    }

    private static long validateSplit(Dataset<Row> data, String name) {
        long count=data.count();
        if (count<20 || data.select("label").distinct().count()!=2)
            throw new IllegalArgumentException(name+" needs at least 20 mature rows and both label classes");
        return count;
    }

    private static Dataset<Row> predictions(PipelineModel model, Dataset<Row> data) {
        return model.transform(data).withColumn("p",vector_to_array(col("probability"),"float64").getItem(1));
    }

    private static Map<String,Double> metrics(Dataset<Row> data) {
        Column p = greatest(lit(1e-15),least(lit(1-1e-15),col("p")));
        Row row = data.agg(avg(col("label").multiply(log(p)).plus(lit(1).minus(col("label")).multiply(log(lit(1).minus(p)))).multiply(-1)),
                avg(pow(col("p").minus(col("label")),2))).first();
        return Map.of("logLoss",row.getDouble(0),"brier",row.getDouble(1));
    }

    private static double ndcg(Dataset<Row> data, String score) {
        Dataset<Row> ranks=data.withColumn("rank",row_number().over(Window.partitionBy("requestId").orderBy(col(score).desc(),col("adId"))))
                .withColumn("idealRank",row_number().over(Window.partitionBy("requestId").orderBy(col("label").desc(),col("adId"))));
        Dataset<Row> groups=ranks.groupBy("requestId").agg(
                sum(when(col("rank").leq(10),col("label").divide(log2(col("rank").plus(1)))).otherwise(0)).alias("dcg"),
                sum(when(col("idealRank").leq(10),col("label").divide(log2(col("idealRank").plus(1)))).otherwise(0)).alias("ideal"));
        Row value=groups.filter(col("ideal").gt(0)).agg(avg(col("dcg").divide(col("ideal")))).first();
        return value.isNullAt(0)?0:value.getDouble(0);
    }

    private static double verifyServingParity(ClickModel model, Dataset<Row> rows) {
        // Distributed aggregate; do not collect a potentially large validation/test set onto the driver.
        var scorer=udf((org.apache.spark.sql.api.java.UDF3<Double,Double,Double,Double>)
                (a,b,c)->model.predict(new double[]{a,b,c}),DataTypes.DoubleType);
        // Capture only a serializable representation; records must be explicitly serializable for Spark closures.
        double max=rows.withColumn("online",scorer.apply(col("textRelevance"),col("smoothedCtr"),col("logImpressions")))
                .agg(max(abs(col("online").minus(col("p"))))).first().getDouble(0);
        if (max>1e-9) throw new IllegalStateException("Offline/online probability mismatch: "+max);
        return max;
    }

    private static void publishFeatures(Dataset<Row> data, Path stage, UUID id, String source,
                                        Instant start, Instant cutoff) throws Exception {
        Dataset<Row> window=data.filter(col("impressionTime").geq(ts(start)).and(col("impressionTime").lt(ts(cutoff))));
        Dataset<Row> aggregate=window.groupBy("adId").agg(count(lit(1)).alias("impressions"),sum("label").cast("long").alias("clickedImpressions"));
        List<Row> collected=aggregate.orderBy("adId").limit(10_001).collectAsList();
        if (collected.size()>10_000) throw new IllegalArgumentException("Snapshot exceeds the supported 10000-ad publication limit");
        List<Map<String,Object>> rows=collected.stream().map(r -> Map.<String,Object>of("adId",r.getString(0),"impressions",r.getLong(1),"clickedImpressions",r.getLong(2))).toList();
        JSON.writerWithDefaultPrettyPrinter().writeValue(stage.resolve("features.json").toFile(),Map.of("id",id.toString(),
                "schemaVersion",ClickModel.SCHEMA,"windowStart",start.toString(),"cutoff",cutoff.toString(),"sourceId",source,"rows",rows));
        window.withColumn("eventDate",to_date(col("impressionTime"))).groupBy("eventDate","adId")
                .agg(count(lit(1)).alias("impressions"),sum("label").cast("long").alias("clickedImpressions"))
                .write().mode(SaveMode.ErrorIfExists).partitionBy("eventDate").parquet(stage.resolve("daily-features.parquet").toString());
    }

    private static void synthetic(Path file, Path catalog, Instant asOf) throws Exception {
        List<String> ads = new ArrayList<>();
        if (catalog != null) {
            for (String line : Files.readAllLines(catalog)) if (!line.isBlank()) ads.add(JSON.readTree(line).path("id").asText());
        } else for (int i=0;i<8;i++) ads.add(UUID.nameUUIDFromBytes(("synthetic-ad-"+i).getBytes(StandardCharsets.UTF_8)).toString());
        if (ads.size()<2) throw new IllegalArgumentException("Synthetic catalog needs at least two ads");
        Random random=new Random(73021);
        try (BufferedWriter writer=Files.newBufferedWriter(file)) {
            for (int request=0;request<800;request++) {
                Instant time=asOf.minus(Duration.ofDays(16)).plusSeconds(request*Duration.ofDays(14).getSeconds()/800);
                String requestId=UUID.nameUUIDFromBytes(("synthetic-request-"+request).getBytes(StandardCharsets.UTF_8)).toString();
                for (int item=0;item<ads.size();item++) {
                    double relevance=0.02+random.nextDouble()*0.48;
                    long impressions=20+item*100;
                    double historicalRate=0.02+0.6*item/Math.max(1,ads.size()-1);
                    double[] vector=ClickModel.features(relevance,impressions,Math.round(impressions*historicalRate));
                    double p=ClickModel.sigmoid(-4+6*vector[0]+5*vector[1]+0.08*vector[2]);
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("impressionId",UUID.nameUUIDFromBytes((requestId+":"+item).getBytes(StandardCharsets.UTF_8)).toString());
                    row.put("requestId",requestId); row.put("adId",ads.get(item)); row.put("predictionTime",time.toString());
                    row.put("impressionTime",time.toString()); row.put("labelAvailableAt",time.plus(Duration.ofHours(25)).toString());
                    row.put("schemaVersion",ClickModel.SCHEMA);
                    for (int f=0;f<3;f++) row.put(ClickModel.FEATURES.get(f),vector[f]);
                    row.put("label",random.nextDouble()<p?1:0);
                    writer.write(JSON.writeValueAsString(row)); writer.newLine();
                }
            }
        }
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static List<Double> doubles(double[] values) { return Arrays.stream(values).boxed().toList(); }
    private static String digest(Path file) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        try (InputStream input=Files.newInputStream(file)) { byte[] buffer=new byte[65536]; int n; while ((n=input.read(buffer))!=-1) sha.update(buffer,0,n); }
        return HexFormat.of().formatHex(sha.digest());
    }
    private static Map<String,String> options(String[] args) {
        Map<String,String> result=new HashMap<>();
        for (int i=0;i<args.length;i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Expected --option value");
            String name=args[i].substring(2);
            if (result.containsKey(name)) throw new IllegalArgumentException("Duplicate option: "+name);
            if (name.equals("synthetic")) result.put(name,"true");
            else if (++i<args.length) result.put(name,args[i]);
            else throw new IllegalArgumentException("Missing value for "+name);
        }
        return result;
    }
    private static String required(Map<String,String> options,String name) {
        String value=options.get(name); if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing --"+name); return value;
    }
}
