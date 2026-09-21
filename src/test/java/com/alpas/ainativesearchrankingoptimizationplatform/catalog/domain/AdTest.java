package com.alpas.ainativesearchrankingoptimizationplatform.catalog.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdTest {
    private static final UUID ID = UUID.fromString("2834ebc2-dfa7-4af3-8e73-d130bb92f421");
    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00.123456789Z");

    @Test
    void normalizesWhitespaceAndTimestampPrecision() {
        Ad ad = new Ad(ID, "  Camera  ", "  Electronics  ", NOW);
        assertEquals("Camera", ad.title());
        assertEquals("Electronics", ad.category());
        assertEquals(Instant.parse("2026-09-21T10:00:00.123456Z"), ad.createdAt());
    }

    @Test
    void acceptsBoundaryLengthsAndRejectsValuesBeyondThem() {
        assertDoesNotThrow(() -> new Ad(ID, "a".repeat(200), "b".repeat(64), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new Ad(ID, "a".repeat(201), "Category", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new Ad(ID, "Title", "b".repeat(65), NOW));
    }

    @Test
    void rejectsAbsentBlankAndControlCharacterFields() {
        for (String title : new String[]{null, "", " \t ", "bad\u0000title", "bad\ntitle"}) {
            assertThrows(IllegalArgumentException.class, () -> new Ad(ID, title, "Category", NOW));
        }
        for (String category : new String[]{null, "", "   ", "bad\rcategory"}) {
            assertThrows(IllegalArgumentException.class, () -> new Ad(ID, "Title", category, NOW));
        }
    }

    @Test
    void retainsUnicodeAndSqlLikeTextAsData() {
        String title = "相机 — O'Reilly; DROP TABLE ads;";
        assertEquals(title, new Ad(ID, title, "Cameras", NOW).title());
        assertDoesNotThrow(() -> new Ad(ID, "📷".repeat(200), "Cameras", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new Ad(ID, "📷".repeat(201), "Cameras", NOW));
    }
}
