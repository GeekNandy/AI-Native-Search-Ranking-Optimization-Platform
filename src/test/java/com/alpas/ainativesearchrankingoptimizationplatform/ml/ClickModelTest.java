package com.alpas.ainativesearchrankingoptimizationplatform.ml;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClickModelTest {
    @Test void usesTrainingPreprocessingAndStableSigmoid() {
        ClickModel model=fixture(List.of(2.0,0.0,0.0),List.of(1.0,0.0,0.0),List.of(2.0,1.0,1.0));
        assertEquals(ClickModel.sigmoid(2),model.predict(new double[]{3,0,0}),1e-12);
        assertEquals(0,ClickModel.sigmoid(-1000)); assertEquals(1,ClickModel.sigmoid(1000));
        assertThrows(IllegalArgumentException.class,()->model.predict(new double[]{Double.NaN,0,0}));
        assertThrows(IllegalArgumentException.class,()->model.predict(new double[]{1,2}));
    }
    @Test void rejectsInvalidArtifactsAndProtectsVectors() {
        assertThrows(IllegalArgumentException.class,()->fixture(List.of(1.0,2.0,3.0),List.of(0.0,0.0,0.0),List.of(0.0,1.0,1.0)));
        ClickModel model=fixture(List.of(1.0,2.0,3.0),List.of(0.0,0.0,0.0),List.of(1.0,1.0,1.0));
        assertThrows(UnsupportedOperationException.class,()->model.coefficients().set(0,99.0));
        assertTrue(model.promotionEligible());
    }
    @Test void definesColdStartAndCountBoundaries() {
        assertArrayEquals(new double[]{0.2,0.05,0},ClickModel.features(0.2,0,0),1e-12);
        assertEquals(0.5,ClickModel.features(0,100,59)[1]);
        assertThrows(IllegalArgumentException.class,()->ClickModel.features(0,3,4));
        assertThrows(IllegalArgumentException.class,()->ClickModel.features(2,0,0));
    }
    @Test void assignmentIsStableAndSeparatesNamespaces() {
        assertEquals(ClickModel.bucket("exp","salt","user"),ClickModel.bucket("exp","salt","user"));
        assertNotEquals(ClickModel.bucket("a","bc"),ClickModel.bucket("ab","c"));
        int treatment=0;
        for (int i=0;i<10000;i++) if(ClickModel.bucket("exp","salt","user-"+i)<5000) treatment++;
        assertTrue(treatment>4700 && treatment<5300,"Hash distribution check: "+treatment);
    }
    private static ClickModel fixture(List<Double> weights,List<Double> means,List<Double> scales) {
        return new ClickModel("test-v1",ClickModel.SCHEMA,ClickModel.FEATURES,means,scales,weights,0,
                Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-02-01T00:00:00Z"),Instant.parse("2026-03-01T00:00:00Z"),
                100,50,50,0.4,0.6,0.4,0.6,0.2,"test-fixture");
    }
}
