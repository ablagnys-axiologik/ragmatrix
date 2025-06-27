package com.bnpp.pf.uk.comp.devops.ragmatrix.controller;

import com.bnpp.pf.uk.comp.devops.ragmatrix.service.GitLabService;
import com.bnpp.pf.uk.comp.devops.ragmatrix.model.RagStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api")
public class StatusController {

    private final GitLabService gitLabService;

    public StatusController(GitLabService gitLabService) {
        this.gitLabService = gitLabService;
    }

    @GetMapping("/status")
    public Mono<List<RagStatus>> getStatus(@RequestParam String groupId) {
        return gitLabService.getRagStatus(groupId);
    }
}