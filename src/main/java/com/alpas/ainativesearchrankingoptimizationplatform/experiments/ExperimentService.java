package com.alpas.ainativesearchrankingoptimizationplatform.experiments;

import com.alpas.ainativesearchrankingoptimizationplatform.ml.ClickModel;
import com.alpas.ainativesearchrankingoptimizationplatform.ml.ModelRegistry;
import com.alpas.ainativesearchrankingoptimizationplatform.platform.PlatformException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperimentService {
    public record Experiment(String id, String salt, String modelVersion, int treatmentPercent) {
        public Experiment {
            ClickModel.identifier(id); ClickModel.identifier(salt); ClickModel.identifier(modelVersion);
            if (treatmentPercent < 10 || treatmentPercent > 90) throw new IllegalArgumentException("Treatment allocation must be 10-90 percent");
        }
    }
    public record Assignment(String experimentId, String arm, String modelVersion) { }
    public record Report(String experimentId, Instant asOf, String metric, int followUpHours, boolean active,
                         ExperimentStatistics.Comparison comparison) { }
    private final JdbcClient jdbc;
    private final ModelRegistry models;
    private final Clock clock;
    public ExperimentService(JdbcClient jdbc, ModelRegistry models, Clock clock) { this.jdbc=jdbc; this.models=models; this.clock=clock; }

    @Transactional
    public boolean create(Experiment experiment) {
        ClickModel model = models.find(experiment.modelVersion()).orElseThrow(() -> PlatformException.missing("Experiment model not found"));
        if (!model.promotionEligible()) throw new PlatformException(422, "Experiment model does not pass the validation gate");
        int inserted = jdbc.sql("""
                INSERT INTO experiments(id,salt,model_version,treatment_percent) VALUES (:id,:salt,:model,:percent)
                ON CONFLICT (id) DO NOTHING
                """).param("id",experiment.id()).param("salt",experiment.salt()).param("model",experiment.modelVersion())
                .param("percent",experiment.treatmentPercent()).update();
        if (!Objects.equals(experiment,find(experiment.id()))) throw PlatformException.conflict("Experiment definitions are immutable");
        return inserted == 1;
    }

    public Experiment find(String id) {
        return jdbc.sql("SELECT id,salt,model_version,treatment_percent FROM experiments WHERE id=:id")
                .param("id",ClickModel.identifier(id)).query((rs,n) -> new Experiment(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4)))
                .optional().orElseThrow(() -> PlatformException.missing("Experiment not found"));
    }

    @Transactional
    public Assignment assign(String id, String user, Instant now) {
        Experiment experiment = find(id);
        if (!active(id)) return null;
        String arm = ClickModel.bucket(experiment.id(),experiment.salt(),user) < experiment.treatmentPercent()*100
                ? "treatment" : "control";
        jdbc.sql("""
                INSERT INTO assignments(experiment_id,user_id,arm,assigned_at) VALUES (:id,:user,:arm,:now)
                ON CONFLICT (experiment_id,user_id) DO NOTHING
                """).param("id",id).param("user",user).param("arm",arm).param("now",now.atOffset(ZoneOffset.UTC)).update();
        String assigned = jdbc.sql("SELECT arm FROM assignments WHERE experiment_id=:id AND user_id=:user")
                .param("id",id).param("user",user).query(String.class).single();
        return new Assignment(id,assigned,"treatment".equals(assigned) ? experiment.modelVersion() : null);
    }

    public Report report(String id, Instant asOf) {
        if (asOf == null || asOf.isAfter(clock.instant())) throw new IllegalArgumentException("Report cutoff is required and cannot be in the future");
        Experiment experiment = find(id);
        // Intent-to-treat: the assignment table supplies the denominator, including users with no exposure or click.
        long[] c = {0,0}, t = {0,0};
        jdbc.sql("""
                SELECT a.arm, count(*) AS users, count(*) FILTER (WHERE EXISTS (
                    SELECT 1 FROM search_requests r JOIN impressions i ON i.request_id=r.id
                    JOIN clicks c ON c.impression_id=i.id
                    WHERE r.experiment_id=a.experiment_id AND r.user_id=a.user_id
                      AND c.occurred_at>=a.assigned_at AND c.occurred_at<a.assigned_at+INTERVAL '24 hours'
                      AND c.received_at<=:asOf AND i.received_at<=:asOf
                )) AS converted
                FROM assignments a WHERE a.experiment_id=:id AND a.assigned_at+INTERVAL '25 hours'<=:asOf
                GROUP BY a.arm
                """).param("id",id).param("asOf",asOf.atOffset(ZoneOffset.UTC)).query((rs,n) -> {
                    long[] counts = "control".equals(rs.getString(1)) ? c : t;
                    counts[0]=rs.getLong(2); counts[1]=rs.getLong(3); return 0;
                }).list();
        return new Report(id,asOf,"assigned-user clicked conversion",24,active(id),
                ExperimentStatistics.compare(c[0],c[1],t[0],t[1],experiment.treatmentPercent()));
    }

    public boolean stop(String id) {
        find(id);
        return jdbc.sql("UPDATE experiments SET active=FALSE WHERE id=:id AND active").param("id",id).update()==1;
    }
    private boolean active(String id) {
        return jdbc.sql("SELECT active FROM experiments WHERE id=:id").param("id",id).query(Boolean.class).single();
    }
}
