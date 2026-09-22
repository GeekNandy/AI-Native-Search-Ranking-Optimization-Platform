package com.alpas.ainativesearchrankingoptimizationplatform.platform;

public class PlatformException extends RuntimeException {
    private final int status;
    public PlatformException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    public static PlatformException conflict(String message) { return new PlatformException(409, message); }
    public static PlatformException missing(String message) { return new PlatformException(404, message); }
}
