package com.bnpp.pf.uk.comp.devops.ragmatrix.model;

import java.util.List;
import java.util.Map;

public class RagStatus {

    public String repoName;
    public String repoPath;

    public boolean gitflowCompliant;
    public boolean masterProtected;
    public boolean developProtected;

    public AgeStats featureAge;
    public AgeStats releaseAge;

    public List<BranchStatus> branches;

    public RagStatus() {}

    public RagStatus(String name, String path, AgeStats featureAge, AgeStats releaseAge) {
        this.repoName = name;
        this.repoPath = path;
        this.featureAge = featureAge;
        this.releaseAge = releaseAge;
    }

    public void setGitflowCompliant(boolean compliant) {
        this.gitflowCompliant = compliant;
    }

    public void setMasterProtected(boolean protectedMaster) {
        this.masterProtected = protectedMaster;
    }

    public void setDevelopProtected(boolean protectedDevelop) {
        this.developProtected = protectedDevelop;
    }

    public void setBranches(List<BranchStatus> branches) {
        this.branches = branches;
    }

    public static class AgeStats {
        public Integer avg;
        public Integer p95;

        public AgeStats(Integer avg, Integer p95) {
            this.avg = avg;
            this.p95 = p95;
        }
    }

    public static class BranchStatus {
        public String branchName;
        public String pipelineStatus;
        public String pipelineUrl;
        public Map<String, String> qualityGates;

        public BranchStatus() {}
    }
}
