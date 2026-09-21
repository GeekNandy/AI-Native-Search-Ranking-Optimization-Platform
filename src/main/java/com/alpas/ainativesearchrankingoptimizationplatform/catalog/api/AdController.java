package com.alpas.ainativesearchrankingoptimizationplatform.catalog.api;

import com.alpas.ainativesearchrankingoptimizationplatform.catalog.application.AdService;
import com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain.Ad;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/ads")
class AdController {
    private final AdService service;

    AdController(AdService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AdResponse> create(@Valid @RequestBody CreateAdRequest request) {
        Ad ad = service.create(request.title(), request.category());
        return ResponseEntity.created(URI.create("/api/v1/ads/" + ad.id()))
                .body(AdResponse.from(ad));
    }

    @GetMapping("/{id}")
    AdResponse get(@PathVariable UUID id) {
        return service.findById(id).map(AdResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ad was not found."));
    }
}
