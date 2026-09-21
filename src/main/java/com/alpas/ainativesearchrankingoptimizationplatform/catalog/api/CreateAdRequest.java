package com.alpas.ainativesearchrankingoptimizationplatform.catalog.api;

import com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain.Ad;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.hibernate.validator.constraints.CodePointLength;

public record CreateAdRequest(
        @NotBlank @CodePointLength(max = Ad.MAX_TITLE_LENGTH)
        @Pattern(regexp = "[^\\p{Cc}]*", message = "must not contain control characters")
        String title,
        @NotBlank @CodePointLength(max = Ad.MAX_CATEGORY_LENGTH)
        @Pattern(regexp = "[^\\p{Cc}]*", message = "must not contain control characters")
        String category) { }
