package com.alpas.ainativesearchrankingoptimizationplatform.catalog.api;

import com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain.Ad;
import java.time.Instant;
import java.util.UUID;

public record AdResponse(UUID id, String title, String category, Instant createdAt) {
    static AdResponse from(Ad ad) {
        return new AdResponse(ad.id(), ad.title(), ad.category(), ad.createdAt());
    }
}
