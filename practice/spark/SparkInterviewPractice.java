import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.expressions.WindowSpec;
import org.apache.spark.sql.types.StructType;
import scala.Tuple2;

import static org.apache.spark.sql.functions.*;

/**
 * Java 17 + Apache Spark 4.0.1. Ten synthetic interview exercises.
 * Reference solutions with named test methods; no JUnit or Python code.
 * Use --learner --lab 1 to check Lab01Ctr.compute instead of the reference.
 * Run with the supplied run.sh and an installed Spark 4.0.1 distribution.
 * Small driver collections are fixture assertions, not large-data designs.
 */
public class SparkInterviewPractice {
    static final String DAY = "2026-09-01";
    static final String CUTOFF = "2026-09-04 00:00:00";

    // BEGIN:ratio
    static Column ratio(Column numerator, Column denominator) {
        return when(denominator.gt(0), numerator.cast("double").divide(denominator));
    }
    // END:ratio

    // BEGIN:requireUnique
    static void requireUnique(Dataset<Row> df, String key) {
        if (df.filter(col(key).isNull()).limit(1).count() != 0)
            throw new IllegalArgumentException("Null key: " + key);
        if (df.groupBy(key).count().filter("count > 1").limit(1).count() != 0)
            throw new IllegalArgumentException("Duplicate key: " + key);
    }
    // END:requireUnique

    // BEGIN:eligibleImpressions
    static Dataset<Row> eligibleImpressions(Dataset<Row> impressions) {
        return impressions.filter(to_date(col("impression_ts"))
            .equalTo(lit(DAY).cast("date"))
            .and(col("received_ts").lt(lit(CUTOFF).cast("timestamp"))));
    }
    // END:eligibleImpressions

    // BEGIN:s01Ctr
    static Dataset<Row> s01Ctr(Dataset<Row> impressions, Dataset<Row> clicks) {
        Dataset<Row> cohort = eligibleImpressions(impressions);
        Dataset<Row> observed = clicks
            .filter(col("received_ts").lt(lit(CUTOFF).cast("timestamp")))
            .dropDuplicates("click_id");
        Column matches = col("c.impression_id").equalTo(col("i.impression_id"))
            .and(col("c.click_ts").geq(col("i.impression_ts")))
            .and(col("c.click_ts").lt(col("i.impression_ts")
                .plus(expr("INTERVAL 24 HOURS"))));
        Dataset<Row> valid = observed.alias("c").join(cohort.alias("i"), matches)
            .select(col("i.impression_id").alias("impression_id"))
            .distinct().withColumn("clicked", lit(1));
        Dataset<Row> totals = cohort
            .join(valid, new String[]{"impression_id"}, "left")
            .groupBy("ad_id").agg(
                count(lit(1)).alias("impressions"),
                sum(coalesce(col("clicked"), lit(0))).alias("clicked"));
        return totals.withColumn("ctr", ratio(col("clicked"), col("impressions")));
    }
    // END:s01Ctr

    // BEGIN:S01_SQL
    static final String S01_SQL = """
        WITH cohort AS (
          SELECT * FROM impressions
          WHERE CAST(impression_ts AS DATE) = DATE '2026-09-01'
            AND received_ts < TIMESTAMP '2026-09-04 00:00:00'
        ), clicked_impressions AS (
          SELECT DISTINCT i.impression_id
          FROM cohort i JOIN clicks c ON i.impression_id = c.impression_id
          WHERE c.received_ts < TIMESTAMP '2026-09-04 00:00:00'
            AND c.click_ts >= i.impression_ts
            AND c.click_ts < i.impression_ts + INTERVAL 24 HOURS
        )
        SELECT i.ad_id, COUNT(*) AS impressions,
               COUNT(c.impression_id) AS clicked,
               CAST(COUNT(c.impression_id) AS DOUBLE) / COUNT(*) AS ctr
        FROM cohort i
        LEFT JOIN clicked_impressions c ON i.impression_id = c.impression_id
        GROUP BY i.ad_id
        """;
    // END:S01_SQL

    // BEGIN:s02Lookup
    static Dataset<Row> s02Lookup(Dataset<Row> facts, Dataset<Row> lookup) {
        requireUnique(lookup, "ad_id");
        return facts.join(broadcast(lookup), new String[]{"ad_id"}, "left")
            .withColumn("category", coalesce(col("category"), lit("UNKNOWN")));
    }
    // END:s02Lookup

    // BEGIN:s03TopThree
    static Dataset<Row> s03TopThree(Dataset<Row> daily) {
        Dataset<Row> eligible = daily.filter(col("impressions").geq(1000))
            .withColumn("ctr", ratio(col("clicked"), col("impressions")));
        WindowSpec w = Window.partitionBy("category")
            .orderBy(col("ctr").desc(), col("ad_id").asc());
        return eligible.withColumn("rank", row_number().over(w))
            .filter("rank <= 3");
    }
    // END:s03TopThree

    // BEGIN:s04Latest
    static Dataset<Row> s04Latest(Dataset<Row> events) {
        Dataset<Row> observed = events.filter(
            col("received_ts").lt(lit(CUTOFF).cast("timestamp")));
        WindowSpec w = Window.partitionBy("event_id").orderBy(
            col("version").desc(), col("received_ts").desc(),
            col("delivery_id").desc());
        return observed.withColumn("rn", row_number().over(w))
            .filter("rn = 1").filter(col("is_deleted").equalTo(false)).drop("rn");
    }
    // END:s04Latest

    // BEGIN:s05Rolling
    static Dataset<Row> s05Rolling(Dataset<Row> daily) {
        Dataset<Row> calendar = daily.select("ad_id").distinct().select(
            col("ad_id"), explode(sequence(lit("2026-09-01").cast("date"),
                lit("2026-09-08").cast("date"))).alias("day"));
        Dataset<Row> dense = calendar
            .join(daily, new String[]{"ad_id", "day"}, "left")
            .na().fill(0L, new String[]{"impressions", "clicked"});
        WindowSpec w = Window.partitionBy("ad_id").orderBy("day")
            .rowsBetween(-6, 0);
        Dataset<Row> totals = dense
            .withColumn("impressions_7d", sum("impressions").over(w))
            .withColumn("clicked_7d", sum("clicked").over(w));
        return totals.withColumn("ctr_7d",
            ratio(col("clicked_7d"), col("impressions_7d")));
    }
    // END:s05Rolling

    // BEGIN:s06SaltedJoin
    static Dataset<Row> s06SaltedJoin(Dataset<Row> facts, Dataset<Row> lookup) {
        requireUnique(lookup, "ad_id");
        Dataset<Row> left = facts.withColumn("salt",
            when(col("ad_id").equalTo("A1"), pmod(col("event_id"), lit(4)))
                .otherwise(lit(0)));
        Dataset<Row> right = lookup.withColumn("salts",
            when(col("ad_id").equalTo("A1"), array(lit(0), lit(1), lit(2), lit(3)))
                .otherwise(array(lit(0))))
            .select(col("ad_id"), col("category"), explode(col("salts")).alias("salt"));
        // Merge hints expose a shuffled example on tiny inputs, not a tuning rule.
        return left.hint("merge").join(right.hint("merge"),
                new String[]{"ad_id", "salt"}, "left")
            .withColumn("category", coalesce(col("category"), lit("UNKNOWN")))
            .drop("salt");
    }
    // END:s06SaltedJoin

    // BEGIN:s07AggregateDataset
    static Dataset<Row> s07AggregateDataset(Dataset<Row> batches) {
        Dataset<Row> totals = batches.groupBy("ad_id").agg(
            sum("clicked").alias("clicked"), sum("impressions").alias("impressions"));
        return totals.withColumn("ctr", ratio(col("clicked"), col("impressions")));
    }
    // END:s07AggregateDataset

    // BEGIN:Counts
    record Counts(long clicked, long impressions) implements Serializable {
        Counts plus(Counts other) {
            return new Counts(Math.addExact(clicked, other.clicked),
                Math.addExact(impressions, other.impressions));
        }
    }
    // END:Counts

    // BEGIN:s07AggregateRdd
    static JavaRDD<Row> s07AggregateRdd(Dataset<Row> batches) {
        JavaPairRDD<String, Counts> pairs = batches.javaRDD().mapToPair(r ->
            new Tuple2<>(r.getAs("ad_id"),
                new Counts(r.<Long>getAs("clicked"), r.<Long>getAs("impressions"))));
        return pairs.reduceByKey(Counts::plus).map(kv -> {
            Counts c = kv._2();
            Double ctr = c.impressions() == 0 ? null
                : (double) c.clicked() / c.impressions();
            return RowFactory.create(kv._1(), c.clicked(), c.impressions(), ctr);
        });
    }
    // END:s07AggregateRdd

    // BEGIN:s08Experiment
    static Dataset<Row> s08Experiment(
            Dataset<Row> assignments, Dataset<Row> conversions) {
        requireUnique(assignments, "user_id");
        Dataset<Row> users = assignments.filter(col("assigned_ts")
            .plus(expr("INTERVAL 24 HOURS")).leq(lit(CUTOFF).cast("timestamp")));
        Dataset<Row> observed = conversions.filter(
            col("received_ts").lt(lit(CUTOFF).cast("timestamp")));
        Column matches = col("a.user_id").equalTo(col("c.user_id"))
            .and(col("c.event_ts").geq(col("a.assigned_ts")))
            .and(col("c.event_ts").lt(col("a.assigned_ts")
                .plus(expr("INTERVAL 24 HOURS"))));
        Dataset<Row> converted = users.alias("a").join(observed.alias("c"), matches)
            .select(col("a.user_id").alias("user_id")).distinct()
            .withColumn("converted", lit(1));
        return users.join(converted, new String[]{"user_id"}, "left")
            .groupBy("arm").agg(count(lit(1)).alias("assigned_users"),
                sum(coalesce(col("converted"), lit(0))).alias("converted_users"))
            .withColumn("conversion_rate",
                ratio(col("converted_users"), col("assigned_users")));
    }
    // END:s08Experiment

    // BEGIN:s09PointInTime
    static Dataset<Row> s09PointInTime(
            Dataset<Row> predictions, Dataset<Row> features) {
        Column matches = col("p.user_id").equalTo(col("f.user_id"))
            .and(col("f.event_ts").leq(col("p.prediction_ts")))
            .and(col("f.available_ts").leq(col("p.prediction_ts")));
        Dataset<Row> joined = predictions.alias("p")
            .join(features.alias("f"), matches, "left");
        WindowSpec w = Window.partitionBy("p.prediction_id").orderBy(
            col("f.event_ts").desc_nulls_last(),
            col("f.available_ts").desc_nulls_last(),
            col("f.feature_record_id").desc_nulls_last());
        return joined.withColumn("rn", row_number().over(w)).filter("rn = 1")
            .select("p.prediction_id", "p.user_id", "p.prediction_ts", "f.feature_value");
    }
    // END:s09PointInTime

    // BEGIN:s10ReadDay
    static Dataset<Row> s10ReadDay(SparkSession spark, String parquetPath) {
        return spark.read().parquet(parquetPath)
            .filter(col("day").equalTo(lit("2026-09-01").cast("date")))
            .select("impression_id", "ad_id", "cost_cents");
    }
    // END:s10ReadDay

    // BEGIN:s10Reuse
    static Dataset<Row> s10Reuse(Dataset<Row> day) {
        Dataset<Row> reusable = day.filter("cost_cents >= 200").cache();
        try {
            long n = reusable.count(); // A required action materialises the cache.
            Dataset<Row> totals = reusable.groupBy("ad_id")
                .agg(sum("cost_cents").alias("cost"));
            totals.show(); // Only this tiny fixture's bounded output.
            totals.explain("formatted");
            return totals; // Safe after unpersist: later actions may recompute.
        } finally {
            reusable.unpersist();
        }
    }
    // END:s10Reuse

    // ---------- Typed synthetic fixtures ----------

    static Dataset<Row> table(SparkSession s, String ddl, Object[][] data,
                              String... timestampColumns) {
        List<Row> rows = new ArrayList<>();
        for (Object[] row : data) rows.add(RowFactory.create(row));
        Dataset<Row> df = s.createDataFrame(rows, StructType.fromDDL(ddl));
        for (String name : timestampColumns) df = df.withColumn(name, to_timestamp(col(name)));
        return df;
    }

    static Dataset<Row> impressions(SparkSession s) {
        return table(s, "impression_id STRING, ad_id STRING, impression_ts STRING, "
            + "received_ts STRING, cost_cents BIGINT", new Object[][] {
            {"i1", "A1", "2026-09-01 10:00:00", "2026-09-01 10:01:00", 100L},
            {"i2", "A1", "2026-09-01 11:00:00", "2026-09-01 11:01:00", 200L},
            {"i3", "A2", "2026-09-01 23:50:00", "2026-09-02 00:01:00", 300L},
            {"i4", "A3", "2026-09-01 12:00:00", "2026-09-01 12:01:00", 400L},
            {"i5", "A1", "2026-09-02 10:00:00", "2026-09-02 10:01:00", 500L},
            {"i6", "A1", "2026-09-01 15:00:00", "2026-09-04 00:00:00", 600L}
        }, "impression_ts", "received_ts");
    }

    static Dataset<Row> clicks(SparkSession s) {
        return table(s, "click_id STRING, impression_id STRING, click_ts STRING, "
            + "received_ts STRING", new Object[][] {
            {"c1", "i1", "2026-09-01 10:05:00", "2026-09-01 10:06:00"},
            {"c1", "i1", "2026-09-01 10:05:00", "2026-09-01 10:06:00"},
            {"c2", "i1", "2026-09-01 10:10:00", "2026-09-01 10:11:00"},
            {"c3", "i3", "2026-09-02 00:05:00", "2026-09-02 00:06:00"},
            {"c4", "i4", "2026-09-02 12:00:00", "2026-09-02 12:01:00"},
            {"c5", "i2", "2026-09-01 10:59:00", "2026-09-01 11:01:00"},
            {"c6", "i2", "2026-09-01 11:05:00", "2026-09-04 00:00:00"},
            {"c7", "missing", "2026-09-01 12:00:00", "2026-09-01 12:01:00"}
        }, "click_ts", "received_ts");
    }

    static Dataset<Row> lookup(SparkSession s) {
        return table(s, "ad_id STRING, category STRING", new Object[][] {
            {"A1", "TECH"}, {"A2", "HOME"}
        });
    }

    static Dataset<Row> rankingData(SparkSession s) {
        return table(s, "category STRING, ad_id STRING, impressions BIGINT, clicked BIGINT",
            new Object[][] {
                {"TECH", "A1", 1000L, 100L}, {"TECH", "A2", 1000L, 100L},
                {"TECH", "A3", 1000L, 90L}, {"TECH", "A4", 1000L, 80L},
                {"TECH", "A5", 10L, 9L}, {"HOME", "A6", 1000L, 0L}
            });
    }

    static Dataset<Row> versionData(SparkSession s) {
        return table(s, "event_id STRING, version BIGINT, received_ts STRING, "
            + "delivery_id STRING, is_deleted BOOLEAN, value BIGINT", new Object[][] {
            {"E1", 1L, "2026-09-01 10:00:00", "d1", false, 10L},
            {"E1", 2L, "2026-09-01 09:00:00", "d2", false, 12L},
            {"E2", 1L, "2026-09-01 08:00:00", "d3", false, 20L},
            {"E2", 2L, "2026-09-01 09:00:00", "d4", true, 0L},
            {"E3", 3L, "2026-09-02 10:00:00", "d5", false, 30L},
            {"E3", 3L, "2026-09-02 10:00:00", "d6", false, 30L},
            {"E4", 1L, "2026-09-01 08:00:00", "d7", false, 40L},
            {"E4", 2L, "2026-09-04 00:00:00", "d8", false, 42L}
        }, "received_ts");
    }

    static Dataset<Row> rollingData(SparkSession s) {
        return table(s, "ad_id STRING, day STRING, impressions BIGINT, clicked BIGINT",
            new Object[][] {
                {"A1", "2026-09-01", 100L, 10L}, {"A1", "2026-09-03", 100L, 20L},
                {"A1", "2026-09-08", 100L, 30L}
            }).withColumn("day", to_date(col("day")));
    }

    static Dataset<Row> skewData(SparkSession s) {
        Object[][] rows = new Object[10][];
        for (int i = 0; i < 10; i++)
            rows[i] = new Object[]{(long)i, i < 8 ? "A1" : i == 8 ? "A2" : "A9", 10L};
        return table(s, "event_id BIGINT, ad_id STRING, cost_cents BIGINT", rows);
    }

    static Dataset<Row> aggregationData(SparkSession s) {
        return table(s, "ad_id STRING, impressions BIGINT, clicked BIGINT", new Object[][] {
            {"A1", 100L, 10L}, {"A1", 1000L, 50L}, {"A2", 0L, 0L}, {"A3", 20L, 4L}
        });
    }

    static Dataset<Row> assignments(SparkSession s) {
        return table(s, "user_id STRING, arm STRING, assigned_ts STRING", new Object[][] {
            {"u1", "A", "2026-09-01 00:00:00"}, {"u2", "A", "2026-09-01 00:00:00"},
            {"u3", "B", "2026-09-01 00:00:00"}, {"u4", "B", "2026-09-01 00:00:00"}
        }, "assigned_ts");
    }

    static Dataset<Row> conversions(SparkSession s) {
        return table(s, "conversion_id STRING, user_id STRING, event_ts STRING, "
            + "received_ts STRING", new Object[][] {
            {"x1", "u1", "2026-09-01 03:00:00", "2026-09-01 03:01:00"},
            {"x1", "u1", "2026-09-01 03:00:00", "2026-09-01 03:01:00"},
            {"x2", "u3", "2026-09-01 04:00:00", "2026-09-01 04:01:00"},
            {"x3", "u4", "2026-09-01 05:00:00", "2026-09-01 05:01:00"},
            {"x3", "u4", "2026-09-01 05:00:00", "2026-09-01 05:01:00"},
            {"x4", "u2", "2026-09-02 00:00:00", "2026-09-02 00:01:00"},
            {"x5", "outsider", "2026-09-01 06:00:00", "2026-09-01 06:01:00"}
        }, "event_ts", "received_ts");
    }

    static Dataset<Row> predictions(SparkSession s) {
        return table(s, "prediction_id STRING, user_id STRING, prediction_ts STRING",
            new Object[][] {
                {"p1", "uA", "2026-09-03 10:00:00"},
                {"p2", "uB", "2026-09-03 10:00:00"}
            }, "prediction_ts");
    }

    static Dataset<Row> features(SparkSession s) {
        return table(s, "feature_record_id STRING, user_id STRING, event_ts STRING, "
            + "available_ts STRING, feature_value DOUBLE", new Object[][] {
            {"f1", "uA", "2026-09-02 08:00:00", "2026-09-02 08:01:00", 1.0},
            {"f2", "uA", "2026-09-03 09:00:00", "2026-09-03 11:00:00", 9.0},
            {"f3", "uA", "2026-09-03 08:00:00", "2026-09-03 08:01:00", 3.0},
            {"f4", "uA", "2026-09-03 10:30:00", "2026-09-03 10:31:00", 4.0}
        }, "event_ts", "available_ts");
    }

    // ---------- Named tests: assertions run without -ea or JUnit ----------

    static List<String> canonicalRows(List<Row> rows) {
        List<String> out = new ArrayList<>();
        for (Row row : rows) {
            List<String> values = new ArrayList<>();
            for (int i = 0; i < row.size(); i++) {
                Object value = row.get(i);
                values.add(value instanceof Float || value instanceof Double
                    ? String.format(Locale.ROOT, "%.10f", ((Number)value).doubleValue())
                    : String.valueOf(value));
            }
            out.add(String.join(" | ", values));
        }
        out.sort(String::compareTo);
        return out;
    }

    static void checkRows(String label, Dataset<Row> actual, Object[][] expected,
                          String... columns) {
        Column[] selected = Arrays.stream(columns).map(org.apache.spark.sql.functions::col)
            .toArray(Column[]::new);
        List<Row> wanted = new ArrayList<>();
        for (Object[] row : expected) wanted.add(RowFactory.create(row));
        checkEqual(label, canonicalRows(actual.select(selected).collectAsList()),
            canonicalRows(wanted));
    }

    static void checkEqual(String label, Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected))
            throw new AssertionError(label + ": actual=" + actual + ", expected=" + expected);
    }

    static void capturePlan(Dataset<Row> df, Path file) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream previous = System.out;
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            df.explain("formatted");
        } finally {
            System.setOut(previous);
        }
        Files.writeString(file, bytes.toString(StandardCharsets.UTF_8));
    }

    static String testS01_CtrBoundariesAndSqlEquivalence(SparkSession s, Path out)
            throws Exception {
        return verifyS01(s, out, SparkInterviewPractice::s01Ctr);
    }

    static String verifyS01(SparkSession s, Path out,
            BiFunction<Dataset<Row>, Dataset<Row>, Dataset<Row>> solution) throws Exception {
        Dataset<Row> i = impressions(s), c = clicks(s);
        Dataset<Row> answer = solution.apply(i, c);
        Object[][] expected = {{"A1", 2L, 1L, 0.5}, {"A2", 1L, 1L, 1.0},
            {"A3", 1L, 0L, 0.0}};
        String[] cols = {"ad_id", "impressions", "clicked", "ctr"};
        checkRows("CTR boundaries and multiplicity", answer, expected, cols);
        i.createOrReplaceTempView("impressions");
        c.createOrReplaceTempView("clicks");
        checkRows("SQL and Java agree", s.sql(S01_SQL), expected, cols);
        capturePlan(answer, out.resolve("s01_ctr_plan.txt"));
        return "A1=2/1/0.5; A2=1/1/1.0; A3=1/0/0.0; SQL=Java";
    }

    static String testS02_LookupPreservesFactsAndRejectsBadKeys(SparkSession s, Path out)
            throws Exception {
        Dataset<Row> facts = eligibleImpressions(impressions(s)), dim = lookup(s);
        Dataset<Row> answer = s02Lookup(facts, dim);
        checkRows("Lookup preservation", answer, new Object[][] {
            {"i1", "TECH", 100L}, {"i2", "TECH", 200L},
            {"i3", "HOME", 300L}, {"i4", "UNKNOWN", 400L}
        }, "impression_id", "category", "cost_cents");
        try {
            s02Lookup(facts, dim.union(dim.filter("ad_id = 'A1'")));
            throw new AssertionError("Duplicate lookup silently accepted");
        } catch (IllegalArgumentException expected) { /* contract rejection */ }
        Dataset<Row> nullKey = table(s, "ad_id STRING, category STRING",
            new Object[][] {{null, "BROKEN"}});
        try {
            s02Lookup(facts, dim.union(nullKey));
            throw new AssertionError("Null lookup key silently accepted");
        } catch (IllegalArgumentException expected) { /* contract rejection */ }
        capturePlan(answer, out.resolve("s02_broadcast_plan.txt"));
        return "4 rows; 1000 cents; one UNKNOWN; duplicate and null keys rejected";
    }

    static String testS03_TopThreeFilterAndTies(SparkSession s, Path out) throws Exception {
        Dataset<Row> answer = s03TopThree(rankingData(s));
        checkRows("Top three", answer, new Object[][] {
            {"HOME", "A6", 1}, {"TECH", "A1", 1}, {"TECH", "A2", 2}, {"TECH", "A3", 3}
        }, "category", "ad_id", "rank");
        capturePlan(answer, out.resolve("s03_window_plan.txt"));
        return "TECH=A1,A2,A3; HOME=A6; low-volume A5 excluded";
    }

    static String testS04_ObservedVersionsAndTombstones(SparkSession s, Path out) {
        checkRows("Latest snapshot", s04Latest(versionData(s)), new Object[][] {
            {"E1", 2L, 12L}, {"E3", 3L, 30L}, {"E4", 1L, 40L}
        }, "event_id", "version", "value");
        return "E1=v2/12; E3=v3/30; E4=v1/40; E2 deleted";
    }

    static String testS05_SevenCalendarDays(SparkSession s, Path out) {
        Dataset<Row> answer = s05Rolling(rollingData(s));
        checkRows("Calendar boundaries", answer.filter("day >= DATE '2026-09-07'")
            .withColumn("day", col("day").cast("string")), new Object[][] {
                {"A1", "2026-09-07", 200L, 30L, 0.15},
                {"A1", "2026-09-08", 200L, 50L, 0.25}
            }, "ad_id", "day", "impressions_7d", "clicked_7d", "ctr_7d");
        checkEqual("Dense days", answer.count(), 8L);
        return "8 dense dates; Sep7=200/30/0.15; Sep8=200/50/0.25";
    }

    static String testS06_SaltedJoinMultisetEquivalence(SparkSession s, Path out)
            throws Exception {
        Dataset<Row> facts = skewData(s), dim = lookup(s);
        Dataset<Row> answer = s06SaltedJoin(facts, dim);
        Dataset<Row> baseline = facts.join(dim, new String[]{"ad_id"}, "left")
            .withColumn("category", coalesce(col("category"), lit("UNKNOWN")));
        String[] cols = {"event_id", "ad_id", "cost_cents", "category"};
        Column[] selected = Arrays.stream(cols).map(org.apache.spark.sql.functions::col)
            .toArray(Column[]::new);
        checkEqual("Salted and baseline multisets", canonicalRows(answer.select(selected)
            .collectAsList()), canonicalRows(baseline.select(selected).collectAsList()));
        checkRows("Count and cost", answer.agg(count(lit(1)).alias("n"),
            sum("cost_cents").alias("cost")), new Object[][]{{10L, 100L}}, "n", "cost");
        capturePlan(answer, out.resolve("s06_salted_join_plan.txt"));
        return "10 rows; 100 cents; salted and unsalted multisets identical";
    }

    static String testS07_PooledCountsAndPartitionIndependence(SparkSession s, Path out)
            throws Exception {
        Dataset<Row> batches = aggregationData(s);
        Dataset<Row> answer = s07AggregateDataset(batches);
        Object[][] expected = {{"A1", 60L, 1100L, 60.0 / 1100},
            {"A2", 0L, 0L, null}, {"A3", 4L, 20L, 0.2}};
        checkRows("Pooled Dataset counts", answer, expected,
            "ad_id", "clicked", "impressions", "ctr");
        List<Row> wanted = new ArrayList<>();
        for (Object[] row : expected) wanted.add(RowFactory.create(row));
        for (int n : new int[]{2, 3})
            checkEqual("Java RDD with " + n + " partitions",
                canonicalRows(s07AggregateRdd(batches.repartition(n)).collect()),
                canonicalRows(wanted));
        capturePlan(answer, out.resolve("s07_partial_aggregate_plan.txt"));
        return "A1=60/1100; A2 CTR=null; A3=0.2; Dataset=RDD at 2 and 3 partitions";
    }

    static String testS08_AssignmentDenominatorAndMatureWindow(SparkSession s, Path out) {
        checkRows("Assigned-user conversion", s08Experiment(assignments(s), conversions(s)),
            new Object[][]{{"A", 2L, 1L, 0.5}, {"B", 2L, 2L, 1.0}},
            "arm", "assigned_users", "converted_users", "conversion_rate");
        return "A=2 assigned/1 converted/0.5; B=2/2/1.0; not a significance test";
    }

    static String testS09_AvailabilityAndMissingHistory(SparkSession s, Path out) {
        checkRows("Point-in-time features", s09PointInTime(predictions(s), features(s)),
            new Object[][]{{"p1", "uA", 3.0}, {"p2", "uB", null}},
            "prediction_id", "user_id", "feature_value");
        return "p1 feature=3.0; p2 preserved with null; unavailable/future features excluded";
    }

    static String testS10_ParquetPruningAndCacheReuse(SparkSession s, Path out)
            throws Exception {
        Path temp = Files.createTempDirectory("spark-java-prep-");
        try {
            String parquet = temp.resolve("events").toString();
            impressions(s).withColumn("day", to_date(col("impression_ts")))
                .write().partitionBy("day").parquet(parquet);
            Dataset<Row> day = s10ReadDay(s, parquet);
            Object[][] expected = {{"i1", "A1", 100L}, {"i2", "A1", 200L},
                {"i3", "A2", 300L}, {"i4", "A3", 400L}, {"i6", "A1", 600L}};
            String[] cols = {"impression_id", "ad_id", "cost_cents"};
            checkRows("Parquet day, without reporting cutoff", day, expected, cols);
            capturePlan(day, out.resolve("s10_parquet_scan_plan.txt"));
            Dataset<Row> reusable = day.filter("cost_cents >= 200").cache();
            try {
                checkEqual("Cached row count", reusable.count(), 4L);
                Dataset<Row> totals = reusable.groupBy("ad_id")
                    .agg(sum("cost_cents").alias("cost"));
                checkRows("Cache reuse", totals, new Object[][] {
                    {"A1", 800L}, {"A2", 300L}, {"A3", 400L}}, "ad_id", "cost");
                capturePlan(totals, out.resolve("s10_cached_scan_plan.txt"));
                checkRows("Repartition preserves rows", day.repartition(3, col("ad_id")),
                    expected, cols);
                checkRows("Coalesce preserves rows", day.coalesce(1), expected, cols);
            } finally {
                reusable.unpersist();
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.delete(path);
            }
        }
        return "5 scan rows; 4 cached rows; A1=800, A2=300, A3=400; partition ops preserve rows";
    }

    @FunctionalInterface
    interface LabTest { String run(SparkSession s, Path output) throws Exception; }

    public static void main(String[] args) throws Exception {
        int chosen = 0;
        boolean learner = false;
        Path out = Path.of("my_results");
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--learner")) {
                learner = true;
            } else if (args[i].equals("--lab") && i + 1 < args.length) {
                String value = args[++i];
                chosen = value.equals("all") ? 0 : Integer.parseInt(value);
                if (!value.equals("all") && (chosen < 1 || chosen > 10))
                    throw new IllegalArgumentException("Lab must be 1..10 or all");
            } else if (args[i].equals("--output-dir") && i + 1 < args.length) {
                out = Path.of(args[++i]);
            } else throw new IllegalArgumentException(
                "Use [--learner] --lab all|1..10 --output-dir PATH");
        }
        if (learner && chosen == 0) chosen = 1;
        if (learner && chosen != 1)
            throw new IllegalArgumentException("Learner starter currently covers --lab 1");
        Files.createDirectories(out);
        SparkSession spark = SparkSession.builder().appName("SearchRankingSparkPractice")
            .master("local[2]").config("spark.ui.enabled", "false")
            .config("spark.ui.showConsoleProgress", "false")
            .config("spark.sql.shuffle.partitions", "4")
            .config("spark.sql.session.timeZone", "UTC")
            .config("spark.sql.ansi.enabled", "true")
            .config("spark.driver.bindAddress", "127.0.0.1").getOrCreate();
        spark.sparkContext().setLogLevel("ERROR");
        Map<Integer, LabTest> tests = new LinkedHashMap<>();
        tests.put(1, SparkInterviewPractice::testS01_CtrBoundariesAndSqlEquivalence);
        tests.put(2, SparkInterviewPractice::testS02_LookupPreservesFactsAndRejectsBadKeys);
        tests.put(3, SparkInterviewPractice::testS03_TopThreeFilterAndTies);
        tests.put(4, SparkInterviewPractice::testS04_ObservedVersionsAndTombstones);
        tests.put(5, SparkInterviewPractice::testS05_SevenCalendarDays);
        tests.put(6, SparkInterviewPractice::testS06_SaltedJoinMultisetEquivalence);
        tests.put(7, SparkInterviewPractice::testS07_PooledCountsAndPartitionIndependence);
        tests.put(8, SparkInterviewPractice::testS08_AssignmentDenominatorAndMatureWindow);
        tests.put(9, SparkInterviewPractice::testS09_AvailabilityAndMissingHistory);
        tests.put(10, SparkInterviewPractice::testS10_ParquetPruningAndCacheReuse);
        if (learner) tests.put(1, (session, output) ->
            verifyS01(session, output, Lab01Ctr::compute));
        List<String> report = new ArrayList<>();
        report.add("Spark=" + spark.version() + "; Java=" + System.getProperty("java.version")
            + "; mode=local[2]; timezone=UTC; ANSI=true; answers="
            + (learner ? "learner" : "reference"));
        try {
            for (var entry : tests.entrySet()) {
                if (chosen != 0 && entry.getKey() != chosen) continue;
                String result = entry.getValue().run(spark, out);
                String line = String.format(Locale.ROOT, "S%02d PASS | %s", entry.getKey(), result);
                report.add(line);
                System.out.println(line);
            }
            report.add("Local fixtures verify correctness, not production performance.");
            Files.write(out.resolve("verification.txt"), report, StandardCharsets.UTF_8);
            System.out.println("Saved verification and plans to " + out.toAbsolutePath());
        } finally {
            spark.stop();
        }
    }
}
