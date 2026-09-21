package com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** A catalog entry. Exposure, click, bid, and ranking state belong to later features. */
public record Ad(UUID id, String title, String category, Instant createdAt) {
    public static final int MAX_TITLE_LENGTH = 200;
    public static final int MAX_CATEGORY_LENGTH = 64;

    public Ad {
        Objects.requireNonNull(id, "id");
        title = normalize(title, MAX_TITLE_LENGTH, "title");
        category = normalize(category, MAX_CATEGORY_LENGTH, "category");
        // PostgreSQL timestamps have microsecond precision; preserve create/read equality.
        createdAt = Objects.requireNonNull(createdAt, "createdAt").truncatedTo(ChronoUnit.MICROS);
    }

    private static String normalize(String value, int limit, String field) {
        if (value == null) throw new IllegalArgumentException(field + " must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > limit
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain 1.." + limit
                    + " Unicode code points without control characters");
        }
        return normalized;
    }
}
