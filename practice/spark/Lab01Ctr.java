import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;

import static org.apache.spark.sql.functions.*;

/** Start with docs/exercises/01-clicked-impression-ctr.md at the repository root. */
public class Lab01Ctr {
    /**
     * Return ad_id, impressions, clicked, ctr for the 2026-09-01 UTC cohort.
     * Receipt cutoff: 2026-09-04 00:00:00 exclusive.
     * Click attribution: [impression timestamp, impression timestamp + 24 hours).
     * Keep zero-click ads and count each clicked impression at most once.
     */
    public static Dataset<Row> compute(Dataset<Row> impressions, Dataset<Row> clicks) {
        // Implement this before looking at SparkInterviewPractice.s01Ctr.
        // 1. Select the eligible canonical impression cohort.
        // 2. Find impression IDs with at least one eligible observed click.
        // 3. Left join that set to the cohort and aggregate at the requested grain.
        throw new UnsupportedOperationException(
            "Implement Lab01Ctr.compute; see docs/exercises/01-clicked-impression-ctr.md");
    }
}
