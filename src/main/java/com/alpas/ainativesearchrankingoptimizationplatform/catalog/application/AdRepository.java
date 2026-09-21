package com.alpas.ainativesearchrankingoptimizationplatform.catalog.application;

import com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain.Ad;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for the catalog's current use cases. */
public interface AdRepository {
    void insert(Ad ad);
    Optional<Ad> findById(UUID id);
}
