package com.example.dashboard.service;

import com.example.dashboard.model.RagStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
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

    private final RestTemplate restTemplate = new RestTemplate();

    public Mono<List<RagStatus>> getRagStatus(String groupId) {
        if (groupId.matches("\\d+")) {
            return resolveGroupPathFromId(groupId)
                    .flatMap(this::queryGraphqlForRagStatus);
        } else {
            return queryGraphqlForRagStatus(groupId);
        }
    }

    private Mono<String> resolveGroupPathFromId(String numericId) {
        String url = UriComponentsBuilder.fromHttpUrl(gitlabUrl)
                .path("/api/v4/groups/" + numericId)
                .build().toUriString();

        try {
            Map group = restTemplate.getForObject(url, Map.class);
            String fullPath = (String) group.get("full_path");
            return Mono.just(fullPath);
        } catch (Exception e) {
            e.printStackTrace();
            return Mono.error(new RuntimeException("Failed to resolve group path from ID: " + numericId));
        }
    }

    private Mono<List<RagStatus>> queryGraphqlForRagStatus(String groupPath) {
        String graphqlEndpoint = gitlabUrl + "/api/graphql";

        String query = """
          query getProjects($groupId: ID!) {
            group(fullPath: $groupId) {
              projects(first: 20) {
                nodes {
                  nameWithNamespace
                  fullPath
                  archived
                  id
                }
              }
            }
          }
        """;

        Map<String, Object> variables = Map.of("groupId", groupPath);
        Map<String, Object> requestBody = Map.of("query", query, "variables", variables);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(gitlabToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            Map response = restTemplate.postForObject(graphqlEndpoint, entity, Map.class);

            // Log entire GraphQL response
            System.out.println("GraphQL response: " + response);

            if (response.containsKey("errors")) {
                List errors = (List) response.get("errors");
                return Mono.error(new RuntimeException("GraphQL errors: " + errors));
            }

            return Mono.just(parseProjects(response));
        } catch (Exception e) {
            e.printStackTrace();
            return Mono.error(new RuntimeException("GraphQL query failed: " + e.getMessage()));
        }
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
                String path = (String) project.get("fullPath");
                Integer projectId = Integer.parseInt(((String) project.get("id")).replace("gid://gitlab/Project/", ""));

                // Now call REST API for branches
                String url = UriComponentsBuilder.fromHttpUrl(gitlabUrl)
                        .path("/api/v4/projects/" + projectId + "/repository/branches")
                        .build().toUriString();

                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(gitlabToken);
                HttpEntity<String> entity = new HttpEntity<>(headers);

                List<Map<String, Object>> branches = restTemplate.exchange(
                        url,
                        org.springframework.http.HttpMethod.GET,
                        entity,
                        List.class
                ).getBody();

                List<Long> featureAges = new ArrayList<>();
                List<Long> releaseAges = new ArrayList<>();

                for (Map<String, Object> branch : branches) {
                    String branchName = (String) branch.get("name");
                    Map<String, Object> commit = (Map<String, Object>) branch.get("commit");
                    if (commit == null) continue;

                    String authoredDateStr = (String) commit.get("committed_date");
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
        if (ages == null || ages.isEmpty()) return new RagStatus.AgeStats(null, null);

        double avg = ages.stream().mapToLong(a -> a).average().orElse(0);
        List<Long> sorted = ages.stream().sorted().collect(Collectors.toList());
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        long p95 = sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));

        return new RagStatus.AgeStats((int) avg, (int) p95);
    }
}
