package com.bnpp.pf.uk.comp.devops.ragmatrix.service;

import com.bnpp.pf.uk.comp.devops.ragmatrix.model.RagStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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

    private final WebClient webClient = WebClient.builder().build();

    public Mono<List<RagStatus>> getRagStatus(String groupId) {
        String query = """
          query getProjects($groupId: ID!) {
            group(fullPath: $groupId) {
              projects(first: 20) {
                nodes {
                  nameWithNamespace
                  pathWithNamespace
                  archived
                  repository {
                    branches(first: 50) {
                      nodes {
                        name
                        commit { authoredDate }
                        pipelines(first: 1) {
                          nodes {
                            id status
                            jobs(first: 10) {
                              nodes { name status }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        """;

        Map<String, Object> variables = Map.of("groupId", groupId);

        return webClient.post()
                .uri(gitlabUrl + "/api/graphql")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + gitlabToken)
                .bodyValue(Map.of("query", query, "variables", variables))
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::parseProjects);
    }

    private List<RagStatus> parseProjects(Map<String, Object> response) {
        try {
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            Map<String, Object> group = (Map<String, Object>) data.get("group");
            Map<String, Object> projectsMap = (Map<String, Object>) group.get("projects");
            List<Map<String, Object>> projects = (List<Map<String, Object>>) projectsMap.get("nodes");

            List<RagStatus> result = new ArrayList<>();

            for (Map<String, Object> project : projects) {
                if ((Boolean) project.getOrDefault("archived", false)) continue;

                String name = (String) project.get("nameWithNamespace");
                String path = (String) project.get("pathWithNamespace");

                Map<String, Object> repo = (Map<String, Object>) project.get("repository");
                if (repo == null || !repo.containsKey("branches")) continue;

                Map<String, Object> branchesMap = (Map<String, Object>) repo.get("branches");
                List<Map<String, Object>> branches = (List<Map<String, Object>>) branchesMap.get("nodes");

                List<Long> featureAges = new ArrayList<>();
                List<Long> releaseAges = new ArrayList<>();

                for (Map<String, Object> branch : branches) {
                    String branchName = (String) branch.get("name");
                    Map<String, Object> commit = (Map<String, Object>) branch.get("commit");
                    if (commit == null) continue;

                    String authoredDateStr = (String) commit.get("authoredDate");
                    if (authoredDateStr == null) continue;

                    ZonedDateTime authoredDate = ZonedDateTime.parse(authoredDateStr, DateTimeFormatter.ISO_DATE_TIME);
                    long daysOld = Duration.between(authoredDate.toInstant(), Instant.now()).toDays();

                    if (branchName.startsWith("feature/")) featureAges.add(daysOld);
                    if (branchName.startsWith("release/")) releaseAges.add(daysOld);
                }

                RagStatus rag = new RagStatus(name, path, computeStats(featureAges), computeStats(releaseAges));
                result.add(rag);
            }

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    protected RagStatus.AgeStats computeStats(List<Long> ages) {
        if (ages.isEmpty()) return new RagStatus.AgeStats(null, null);
        double avg = ages.stream().mapToLong(a -> a).average().orElse(0);
        List<Long> sorted = ages.stream().sorted().collect(Collectors.toList());
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        long p95 = sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));

        return new RagStatus.AgeStats((int) avg, (int) p95);
    }
}