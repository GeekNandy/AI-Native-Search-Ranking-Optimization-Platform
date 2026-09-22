package com.alpas.ainativesearchrankingoptimizationplatform.ml;

import com.alpas.ainativesearchrankingoptimizationplatform.platform.PlatformException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ModelRegistry {
    public record Deployment(long revision, String modelVersion, int rolloutPercent) { }
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;
    public ModelRegistry(JdbcClient jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc; this.json = json; this.clock = clock;
    }

    @Transactional
    public boolean register(ClickModel model) {
        if (model.evaluationAsOf().isAfter(clock.instant())) throw new IllegalArgumentException("Model evaluation cutoff is in the future");
        String artifact = json.writeValueAsString(model);
        String hash = ClickModel.sha256(artifact);
        int inserted = jdbc.sql("""
                INSERT INTO models(version, artifact, content_hash) VALUES (:version, CAST(:artifact AS jsonb), :hash)
                ON CONFLICT (version) DO NOTHING
                """).param("version", model.version()).param("artifact", artifact).param("hash", hash).update();
        String stored = jdbc.sql("SELECT content_hash FROM models WHERE version=:version")
                .param("version", model.version()).query(String.class).single();
        if (!hash.equals(stored)) throw PlatformException.conflict("Model version already contains a different artifact");
        return inserted == 1;
    }

    public Optional<ClickModel> find(String version) {
        if (version == null) return Optional.empty();
        return jdbc.sql("SELECT artifact::text FROM models WHERE version=:version")
                .param("version", version).query(String.class).optional()
                .map(value -> json.readValue(value, ClickModel.class));
    }

    public List<String> versions() {
        return jdbc.sql("SELECT version FROM models ORDER BY registered_at DESC, version LIMIT 100")
                .query(String.class).list();
    }

    public Deployment deployment() {
        return jdbc.sql("SELECT revision, model_version, rollout_percent FROM ranking_deployment WHERE singleton")
                .query((rs, n) -> new Deployment(rs.getLong(1), rs.getString(2), rs.getInt(3))).single();
    }

    @Transactional
    public Deployment deploy(Deployment expected) {
        if (expected.revision() < 0 || expected.rolloutPercent() < 0 || expected.rolloutPercent() > 100
                || (expected.modelVersion() == null && expected.rolloutPercent() != 0))
            throw new IllegalArgumentException("Invalid deployment revision or rollout percentage");
        if (expected.modelVersion() != null) {
            ClickModel model = find(ClickModel.identifier(expected.modelVersion()))
                    .orElseThrow(() -> PlatformException.missing("Model version not found"));
            if (!model.promotionEligible()) throw new PlatformException(422, "Validation log loss exceeds the training-prior baseline");
        }
        int changed = jdbc.sql("""
                UPDATE ranking_deployment SET revision=revision+1, model_version=:model, rollout_percent=:percent
                WHERE singleton AND revision=:revision
                """).param("model", expected.modelVersion()).param("percent", expected.rolloutPercent())
                .param("revision", expected.revision()).update();
        if (changed != 1) throw PlatformException.conflict("Deployment changed; read its revision before retrying");
        Deployment next = new Deployment(expected.revision() + 1, expected.modelVersion(), expected.rolloutPercent());
        jdbc.sql("INSERT INTO deployment_history(revision, model_version, rollout_percent) VALUES (:revision,:model,:percent)")
                .param("revision", next.revision()).param("model", next.modelVersion()).param("percent", next.rolloutPercent()).update();
        return next;
    }

    public List<Deployment> history() {
        return jdbc.sql("SELECT revision, model_version, rollout_percent FROM deployment_history ORDER BY revision DESC LIMIT 100")
                .query((rs, n) -> new Deployment(rs.getLong(1), rs.getString(2), rs.getInt(3))).list();
    }
}
