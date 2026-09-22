package com.alpas.ainativesearchrankingoptimizationplatform.experiments;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ExperimentStatisticsTest {
    @Test void retainsNonConvertersAndUsesUserLevelUncertainty() {
        var report=ExperimentStatistics.compare(1000,100,1000,130,50);
        assertEquals(0.03,report.absoluteLift(),1e-12);
        assertTrue(report.liftLower95()<report.absoluteLift());
        assertTrue(report.liftUpper95()>report.absoluteLift());
        assertFalse(report.sampleRatioMismatch()); assertFalse(report.smallSample());
    }
    @Test void emptyAndSmallSamplesDoNotClaimAnEffect() {
        var empty=ExperimentStatistics.compare(0,0,0,0,50);
        assertNull(empty.absoluteLift()); assertTrue(empty.smallSample());
        var tiny=ExperimentStatistics.compare(1,0,1,1,50);
        assertTrue(tiny.smallSample()); assertTrue(tiny.liftLower95()<0);
    }
    @Test void detectsAllocationMismatchWithAdequateCounts() {
        assertTrue(ExperimentStatistics.compare(900,100,100,10,50).sampleRatioMismatch());
        assertFalse(ExperimentStatistics.compare(900,100,100,10,10).sampleRatioMismatch());
    }
    @Test void intervalsStayDefinedAtZeroAndOne() {
        var report=ExperimentStatistics.compare(100,0,100,100,50);
        assertEquals(0,report.control().lower95(),1e-12);
        assertEquals(1,report.treatment().upper95(),1e-12);
        assertThrows(IllegalArgumentException.class,()->ExperimentStatistics.compare(1,2,1,0,50));
    }
}
