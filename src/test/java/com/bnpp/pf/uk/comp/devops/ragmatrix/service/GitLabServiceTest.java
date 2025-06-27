package com.bnpp.pf.uk.comp.devops.ragmatrix.service;

import com.bnpp.pf.uk.comp.devops.ragmatrix.model.RagStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class GitLabServiceTest {

    @Test
    public void testComputeStats() {
        GitLabService service = new GitLabService();
        List<Long> input = List.of(1L, 2L, 3L, 4L, 20L);
        RagStatus.AgeStats stats = service.computeStats(input);
        assertEquals(6, stats.avg);
        assertEquals(20, stats.p95);
    }
}
