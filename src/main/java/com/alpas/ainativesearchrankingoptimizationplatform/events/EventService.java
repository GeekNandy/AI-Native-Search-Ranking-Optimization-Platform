package com.alpas.ainativesearchrankingoptimizationplatform.events;

import com.alpas.ainativesearchrankingoptimizationplatform.platform.PlatformException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {
    public record Impression(UUID id, UUID requestId, UUID adId, Instant occurredAt) {
        public Impression {
            if (id == null || requestId == null || adId == null || occurredAt == null)
                throw new IllegalArgumentException("Impression identifiers and event time are required");
            occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        }
    }
    public record Click(UUID id, UUID impressionId, Instant occurredAt) {
        public Click {
            if (id == null || impressionId == null || occurredAt == null)
                throw new IllegalArgumentException("Click identifiers and event time are required");
            occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
        }
    }
    public record Receipt(UUID id, boolean created) { }
    private final JdbcClient jdbc;
    private final Clock clock;
    private final MeterRegistry meters;
    public EventService(JdbcClient jdbc, Clock clock, MeterRegistry meters) { this.jdbc=jdbc; this.clock=clock; this.meters=meters; }

    @Transactional
    public Receipt impression(Impression event) {
        Impression previous = storedImpression(event.id());
        if (previous != null) return replay(event.id(),event,previous,"impression");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        validateClock(event.occurredAt(),now);
        Instant returned = jdbc.sql("""
                SELECT r.created_at FROM search_requests r JOIN search_results s ON s.request_id=r.id
                WHERE r.id=:request AND s.ad_id=:ad
                """).param("request",event.requestId()).param("ad",event.adId())
                .query((rs,n)->rs.getObject(1,OffsetDateTime.class).toInstant()).optional()
                .orElseThrow(()->PlatformException.missing("Search result not found"));
        if (event.occurredAt().isBefore(returned) || !event.occurredAt().isBefore(returned.plus(Duration.ofHours(1))))
            throw new IllegalArgumentException("Exposure must occur within one hour after the search");
        int inserted = jdbc.sql("""
                INSERT INTO impressions(id,request_id,ad_id,occurred_at,received_at)
                VALUES (:id,:request,:ad,:occurred,:received) ON CONFLICT DO NOTHING
                """).param("id",event.id()).param("request",event.requestId()).param("ad",event.adId())
                .param("occurred",event.occurredAt().atOffset(ZoneOffset.UTC)).param("received",now.atOffset(ZoneOffset.UTC)).update();
        if (inserted == 0) return replay(event.id(),event,storedImpression(event.id()),"impression");
        meters.counter("platform.events.accepted","type","impression").increment();
        return new Receipt(event.id(),true);
    }

    @Transactional
    public Receipt click(Click event) {
        Click previous = storedClick(event.id());
        if (previous != null) return replay(event.id(),event,previous,"click");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        validateClock(event.occurredAt(),now);
        Impression impression = storedImpression(event.impressionId());
        if (impression == null) throw PlatformException.conflict("Impression has not arrived; retry after recording the exposure");
        if (event.occurredAt().isBefore(impression.occurredAt())
                || !event.occurredAt().isBefore(impression.occurredAt().plus(Duration.ofHours(24))))
            throw new IllegalArgumentException("Click is outside the 24-hour attribution window");
        int inserted = jdbc.sql("""
                INSERT INTO clicks(id,impression_id,occurred_at,received_at) VALUES (:id,:impression,:occurred,:received)
                ON CONFLICT (id) DO NOTHING
                """).param("id",event.id()).param("impression",event.impressionId())
                .param("occurred",event.occurredAt().atOffset(ZoneOffset.UTC)).param("received",now.atOffset(ZoneOffset.UTC)).update();
        if (inserted == 0) return replay(event.id(),event,storedClick(event.id()),"click");
        meters.counter("platform.events.accepted","type","click").increment();
        return new Receipt(event.id(),true);
    }

    private Impression storedImpression(UUID id) {
        return jdbc.sql("SELECT id,request_id,ad_id,occurred_at FROM impressions WHERE id=:id").param("id",id)
                .query((rs,n)->new Impression(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,UUID.class),rs.getObject(4,OffsetDateTime.class).toInstant())).optional().orElse(null);
    }
    private Click storedClick(UUID id) {
        return jdbc.sql("SELECT id,impression_id,occurred_at FROM clicks WHERE id=:id").param("id",id)
                .query((rs,n)->new Click(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                        rs.getObject(3,OffsetDateTime.class).toInstant())).optional().orElse(null);
    }
    private Receipt replay(UUID id, Object event, Object previous, String type) {
        if (!Objects.equals(event,previous)) {
            meters.counter("platform.events.conflicts","type",type).increment();
            throw PlatformException.conflict("Event ID or exposure identity conflicts with an existing event");
        }
        meters.counter("platform.events.replays","type",type).increment();
        return new Receipt(id,false);
    }
    private static void validateClock(Instant event, Instant now) {
        if (event.isAfter(now) || event.isBefore(now.minus(Duration.ofHours(1))))
            throw new IllegalArgumentException("New events must arrive within one hour of their event time and cannot be in the future");
    }
}
