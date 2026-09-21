package com.alpas.ainativesearchrankingoptimizationplatform.catalog.infrastructure;

import com.alpas.ainativesearchrankingoptimizationplatform.catalog.application.AdRepository;
import com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain.Ad;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdRepository implements AdRepository {
    private static final RowMapper<Ad> ROW_MAPPER = (rs, rowNumber) -> new Ad(
            rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("category"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    JdbcAdRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Ad ad) {
        jdbc.sql("""
                INSERT INTO ads (id, title, category, created_at)
                VALUES (:id, :title, :category, :createdAt)
                """)
                .param("id", ad.id())
                .param("title", ad.title())
                .param("category", ad.category())
                .param("createdAt", ad.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @Override
    public Optional<Ad> findById(UUID id) {
        return jdbc.sql("SELECT id, title, category, created_at FROM ads WHERE id = :id")
                .param("id", id).query(ROW_MAPPER).optional();
    }
}
