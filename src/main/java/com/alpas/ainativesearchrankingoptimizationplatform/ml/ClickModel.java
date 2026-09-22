package com.alpas.ainativesearchrankingoptimizationplatform.ml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** A data-only serving artifact. The Spark runner compiles this same source. */
public record ClickModel(String version, String schemaVersion, List<String> featureNames,
                         List<Double> means, List<Double> scales, List<Double> coefficients,
                         double intercept, Instant trainedThrough, Instant validationThrough, Instant evaluationAsOf, long trainingRows,
                         long validationRows, long testRows, double validationLogLoss,
                         double baselineValidationLogLoss, double testLogLoss,
                         double baselineTestLogLoss, double testBrierScore, String sourceId) implements java.io.Serializable {
    public static final String SCHEMA = "click-v1";
    public static final List<String> FEATURES = List.of("textRelevance", "smoothedCtr", "logImpressions");

    public ClickModel {
        identifier(version);
        if (!SCHEMA.equals(schemaVersion) || !FEATURES.equals(featureNames))
            throw new IllegalArgumentException("Unsupported model feature schema or order");
        featureNames = List.copyOf(featureNames);
        means = vector(means); scales = vector(scales); coefficients = vector(coefficients);
        if (scales.stream().anyMatch(v -> v < 1e-12) || !Double.isFinite(intercept)
                || Math.abs(intercept) > 1e6 || trainedThrough == null
                || validationThrough == null || evaluationAsOf == null
                || !trainedThrough.isBefore(validationThrough) || !validationThrough.isBefore(evaluationAsOf)
                || trainingRows < 2 || validationRows < 2 || testRows < 2)
            throw new IllegalArgumentException("Invalid model parameters or evaluation sample sizes");
        for (double value : new double[]{validationLogLoss, baselineValidationLogLoss,
                testLogLoss, baselineTestLogLoss, testBrierScore})
            if (!Double.isFinite(value) || value < 0 || value > 100)
                throw new IllegalArgumentException("Invalid evaluation metric");
        if (testBrierScore > 1 || sourceId == null || sourceId.isBlank() || sourceId.length() > 128
                || sourceId.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid provenance or Brier score");
    }

    public boolean promotionEligible() { return validationLogLoss <= baselineValidationLogLoss; }

    public double predict(double[] features) {
        if (features.length != FEATURES.size()) throw new IllegalArgumentException("Feature count mismatch");
        double z = intercept;
        for (int i = 0; i < features.length; i++) {
            if (!Double.isFinite(features[i])) throw new IllegalArgumentException("Non-finite feature");
            z += coefficients.get(i) * ((features[i] - means.get(i)) / scales.get(i));
        }
        if (!Double.isFinite(z)) throw new IllegalArgumentException("Non-finite model score");
        return sigmoid(z);
    }

    public static double sigmoid(double z) {
        return z >= 0 ? 1 / (1 + Math.exp(-z)) : Math.exp(z) / (1 + Math.exp(z));
    }

    public static double[] features(double relevance, long impressions, long clicks) {
        if (!Double.isFinite(relevance) || relevance < 0 || relevance > 1 || impressions < 0
                || impressions > 1_000_000_000_000L || clicks < 0 || clicks > impressions)
            throw new IllegalArgumentException("Invalid feature counts or relevance");
        return new double[]{relevance, (clicks + 1.0) / (impressions + 20.0), Math.log1p(impressions)};
    }

    public static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))
            throw new IllegalArgumentException("Identifier must contain 1-80 letters, digits, dots, underscores or hyphens");
        return value;
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Length-prefix each input to avoid concatenation ambiguity. */
    public static int bucket(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) key.append(part.length()).append(':').append(part);
        return (int) (Long.parseUnsignedLong(sha256(key.toString()).substring(0, 8), 16) % 10_000);
    }

    private static List<Double> vector(List<Double> values) {
        if (values == null || values.size() != FEATURES.size()
                || values.stream().anyMatch(v -> v == null || !Double.isFinite(v) || Math.abs(v) > 1e6))
            throw new IllegalArgumentException("Invalid model vector");
        return List.copyOf(values);
    }
}
