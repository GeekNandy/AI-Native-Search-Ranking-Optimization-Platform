package com.alpas.ainativesearchrankingoptimizationplatform.catalog.application;

import com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain.Ad;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Framework-independent use cases; successful creation requires a completed insert. */
public final class AdService {
    private final AdRepository repository;
    private final Clock clock;

    public AdService(AdRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Ad create(String title, String category) {
        Ad ad = new Ad(UUID.randomUUID(), title, category, Instant.now(clock));
        repository.insert(ad);
        return ad;
    }

    public Optional<Ad> findById(UUID id) {
        return repository.findById(Objects.requireNonNull(id, "id"));
    }
}
