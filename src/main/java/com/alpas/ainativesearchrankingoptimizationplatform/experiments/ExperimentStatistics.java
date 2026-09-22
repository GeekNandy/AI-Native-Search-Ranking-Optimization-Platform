package com.alpas.ainativesearchrankingoptimizationplatform.experiments;

public final class ExperimentStatistics {
    private ExperimentStatistics() { }
    public record Arm(long users, long convertedUsers, Double rate, Double lower95, Double upper95) { }
    public record Comparison(Arm control, Arm treatment, Double absoluteLift,
                             Double liftLower95, Double liftUpper95, double allocationChiSquare,
                             boolean sampleRatioMismatch, boolean smallSample) { }

    public static Comparison compare(long controlUsers, long controlConversions,
                                     long treatmentUsers, long treatmentConversions, int treatmentPercent) {
        if (treatmentPercent < 10 || treatmentPercent > 90) throw new IllegalArgumentException("Invalid allocation");
        Arm c = arm(controlUsers, controlConversions), t = arm(treatmentUsers, treatmentConversions);
        double total = (double) controlUsers + treatmentUsers;
        double expectedT = total * treatmentPercent / 100.0, expectedC = total - expectedT;
        double chi = total == 0 ? 0 : Math.pow(treatmentUsers - expectedT, 2) / expectedT
                + Math.pow(controlUsers - expectedC, 2) / expectedC;
        boolean small = expectedT < 5 || expectedC < 5 || controlUsers < 100 || treatmentUsers < 100;
        if (c.rate() == null || t.rate() == null)
            return new Comparison(c,t,null,null,null,chi,false,true);
        double difference = t.rate() - c.rate();
        double lower = difference - Math.hypot(t.rate()-t.lower95(), c.upper95()-c.rate());
        double upper = difference + Math.hypot(t.upper95()-t.rate(), c.rate()-c.lower95());
        return new Comparison(c,t,difference,Math.max(-1,lower),Math.min(1,upper),chi,
                expectedT >= 5 && expectedC >= 5 && chi > 10.8275661707,small);
    }

    private static Arm arm(long n, long successes) {
        if (n < 0 || successes < 0 || successes > n) throw new IllegalArgumentException("Invalid experiment counts");
        if (n == 0) return new Arm(0,0,null,null,null);
        double z = 1.95996398454, p = (double) successes/n, denominator = 1+z*z/n;
        double center = (p+z*z/(2*n))/denominator;
        double half = z*Math.sqrt(p*(1-p)/n+z*z/(4.0*n*n))/denominator;
        return new Arm(n,successes,p,Math.max(0,center-half),Math.min(1,center+half));
    }
}
