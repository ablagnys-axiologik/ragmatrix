package com.bnpp.pf.uk.comp.devops.ragmatrix.model;

public class RagStatus {
    public String name;
    public String path;
    public AgeStats featureStats;
    public AgeStats releaseStats;

    public RagStatus(String name, String path, AgeStats f, AgeStats r) {
        this.name = name;
        this.path = path;
        this.featureStats = f;
        this.releaseStats = r;
    }

    public static class AgeStats {
        public Integer avg;
        public Integer p95;

        public AgeStats(Integer avg, Integer p95) {
            this.avg = avg;
            this.p95 = p95;
        }
    }
}