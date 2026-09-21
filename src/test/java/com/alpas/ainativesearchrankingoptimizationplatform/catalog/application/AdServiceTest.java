package com.alpas.ainativesearchrankingoptimizationplatform.catalog.application;

import static org.junit.jupiter.api.Assertions.*;

import com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain.Ad;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsAndRetrievesPersistedAdUsingTheSuppliedClock() {
        MemoryRepository repository = new MemoryRepository();
        AdService service = new AdService(repository, CLOCK);
        Ad ad = service.create("Camera", "Electronics");
        assertEquals(CLOCK.instant(), ad.createdAt());
        assertEquals(Optional.of(ad), service.findById(ad.id()));
        assertTrue(service.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void invalidInputNeverReachesPersistence() {
        MemoryRepository repository = new MemoryRepository();
        AdService service = new AdService(repository, CLOCK);
        assertThrows(IllegalArgumentException.class, () -> service.create(" ", "Electronics"));
        assertTrue(repository.rows.isEmpty());
    }

    @Test
    void propagatesPersistenceFailureWithoutReturningSuccess() {
        RuntimeException failure = new RuntimeException("Storage failed");
        MemoryRepository repository = new MemoryRepository() {
            @Override public void insert(Ad ad) { throw failure; }
        };
        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> new AdService(repository, CLOCK).create("Camera", "Electronics"));
        assertSame(failure, actual);
    }

    @Test
    void separateCreateCallsHaveSeparateIdentities() {
        AdService service = new AdService(new MemoryRepository(), CLOCK);
        assertNotEquals(service.create("Camera", "Electronics").id(),
                service.create("Camera", "Electronics").id());
    }

    private static class MemoryRepository implements AdRepository {
        final Map<UUID, Ad> rows = new HashMap<>();
        @Override public void insert(Ad ad) { rows.put(ad.id(), ad); }
        @Override public Optional<Ad> findById(UUID id) { return Optional.ofNullable(rows.get(id)); }
    }
}
