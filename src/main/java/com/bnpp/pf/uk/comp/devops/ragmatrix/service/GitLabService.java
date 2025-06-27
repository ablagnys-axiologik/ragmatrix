package com.bnpp.pf.uk.comp.devops.ragmatrix.service;

import com.bnpp.pf.uk.comp.devops.ragmatrix.model.RagStatus;
import com.bnpp.pf.uk.comp.devops.ragmatrix.model.RagStatus.BranchStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class GitLabService {

    @Value("${gitlab.token}")
    private String gitlabToken;

    @Value("${gitlab.url}")
    private String gitlabUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public List<RagStatus> getRagStatus(String groupId) {
        try {
            List<Map<String, Object>> projects = fetchProjects(groupId);
            List<RagStatus> result = new ArrayList<>();

            for (Map<String, Object> project : projects) {
                boolean archived = (boolean) project.getOrDefault("archived", false);
                if (archived) continue;

                String name = (String) project.get("name_with_namespace");
                String path = (String) project.get("path_with_namespace");
                Integer projectId = (Integer) project.get("id");

                List<Map<String, Object>> branches = fetchBranches(projectId);
                List<Long> featureAges = new ArrayList<>();
                List<Long> releaseAges = new ArrayList<>();
                List<String> nonCompliant = new ArrayList<>();
                boolean masterProtected = isBranchProtected(projectId, "master");
                boolean developProtected = isBranchProtected(projectId, "develop");

                List<BranchStatus> branchStatuses = new ArrayList<>();

                for (Map<String, Object> branch : branches) {
                    String branchName = (String) branch.get("name");
                    Map<String, Object> commit = (Map<String, Object>) branch.get("commit");
                    if (commit == null) continue;

                    String commitDate = (String) commit.get("committed_date");
                    ZonedDateTime date = ZonedDateTime.parse(commitDate, DateTimeFormatter.ISO_DATE_TIME);
                    long ageDays = Duration.between(date.toInstant(), Instant.now()).toDays();

                    if (branchName.startsWith("feature/")) featureAges.add(ageDays);
                    else if (branchName.startsWith("release/")) releaseAges.add(ageDays);
                    else if (!List.of("master", "develop").contains(branchName) && !branchName.startsWith("hotfix/")) {
                        nonCompliant.add(branchName);
                    }

                    BranchStatus bs = new BranchStatus();
                    bs.branchName = branchName;
                    bs.pipelineStatus = "UNKNOWN";
                    bs.pipelineUrl = "";
                    bs.qualityGates = new HashMap<>();

                    try {
                        List<Map<String, Object>> pipelines = fetchPipelines(projectId, branchName);
                        if (!pipelines.isEmpty()) {
                            Map<String, Object> latest = pipelines.get(0);
                            bs.pipelineStatus = (String) latest.get("status");
                            int pipelineId = (Integer) latest.get("id");
                            bs.pipelineUrl = gitlabUrl + "/" + path + "/-/pipelines/" + pipelineId;

                            List<Map<String, Object>> jobs = fetchJobs(projectId, pipelineId);
                            for (Map<String, Object> job : jobs) {
                                String nameLower = ((String) job.get("name")).toLowerCase();
                                String status = (String) job.get("status");

                                if (nameLower.contains("sonar")) bs.qualityGates.put("Sonar", status);
                                if (nameLower.contains("fortify")) bs.qualityGates.put("Fortify", status);
                                if (nameLower.contains("nexusiq")) bs.qualityGates.put("NexusIQ", status);
                                if (nameLower.contains("sysdig")) bs.qualityGates.put("Sysdig", status);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    branchStatuses.add(bs);
                }

                RagStatus rag = new RagStatus(name, path, computeStats(featureAges), computeStats(releaseAges));
                rag.setGitflowCompliant(nonCompliant.isEmpty());
                rag.setMasterProtected(masterProtected);
                rag.setDevelopProtected(developProtected);
                rag.setBranches(branchStatuses);

                result.add(rag);
            }

            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load RAG data: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> fetchProjects(String groupId) {
        String url = UriComponentsBuilder.fromHttpUrl(gitlabUrl + "/api/v4/groups/" + groupId + "/projects")
                .queryParam("include_subgroups", "true")
                .queryParam("per_page", 100)
                .build().toUriString();
        return sendGet(url);
    }

    private List<Map<String, Object>> fetchBranches(Integer projectId) {
        String url = gitlabUrl + "/api/v4/projects/" + projectId + "/repository/branches?per_page=100";
        return sendGet(url);
    }

    private List<Map<String, Object>> fetchPipelines(Integer projectId, String branch) {
        String url = UriComponentsBuilder.fromHttpUrl(gitlabUrl + "/api/v4/projects/" + projectId + "/pipelines")
                .queryParam("ref", branch)
                .queryParam("per_page", 1)
                .build().toUriString();
        return sendGet(url);
    }

    private List<Map<String, Object>> fetchJobs(Integer projectId, Integer pipelineId) {
        String url = gitlabUrl + "/api/v4/projects/" + projectId + "/pipelines/" + pipelineId + "/jobs";
        return sendGet(url);
    }

    private boolean isBranchProtected(Integer projectId, String branch) {
        String url = gitlabUrl + "/api/v4/projects/" + projectId + "/protected_branches/" + branch;
        try {
            restTemplate.exchange(url, HttpMethod.GET, buildEntity(), Map.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private <T> List<T> sendGet(String url) {
        ResponseEntity<List> res = restTemplate.exchange(url, HttpMethod.GET, buildEntity(), List.class);
        return res.getBody();
    }

    private HttpEntity<?> buildEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(gitlabToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        return new HttpEntity<>(headers);
    }

    protected RagStatus.AgeStats computeStats(List<Long> ages) {
        if (ages == null || ages.isEmpty()) return new RagStatus.AgeStats(null, null);
        double avg = ages.stream().mapToLong(a -> a).average().orElse(0);
        List<Long> sorted = new ArrayList<>(ages);
        Collections.sort(sorted);
        int p95Index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        long p95 = sorted.get(Math.min(p95Index, sorted.size() - 1));
        return new RagStatus.AgeStats((int) avg, (int) p95);
    }

    private String fetchSonarCoverage(String projectKey, String branch) {
    try {
        String apiUrl = UriComponentsBuilder.fromHttpUrl(sonarUrl + "/api/measures/component")
            .queryParam("component", projectKey)
            .queryParam("metricKeys", "coverage")
            .queryParam("branch", branch)
            .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(sonarToken, "");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        Map response = restTemplate.exchange(apiUrl, HttpMethod.GET, entity, Map.class).getBody();
        Map component = (Map) response.get("component");
        List<Map<String, String>> measures = (List<Map<String, String>>) component.get("measures");

        for (Map<String, String> m : measures) {
            if ("coverage".equals(m.get("metric"))) {
                return m.get("value") + "%";
            }
        }
    } catch (Exception e) {
        System.err.println("Failed to fetch Sonar coverage: " + e.getMessage());
    }
    return "N/A";
}

}
