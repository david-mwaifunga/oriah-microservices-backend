package com.oriah.search.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.search.dto.SearchResultResponse;
import com.oriah.search.repository.SearchIndexRepository;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SearchIndexRepository repository;

    public SearchController(SearchIndexRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<SearchResultResponse> search(
            @RequestParam("q") String q,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        if (q == null || q.isBlank()) {
            return ResponseEntity.ok(new SearchResultResponse(q, 0, List.of()));
        }
        var hits = repository.search(q, Math.min(limit, 100)).stream()
                .map(SearchResultResponse.Hit::from)
                .toList();
        return ResponseEntity.ok(new SearchResultResponse(q, hits.size(), hits));
    }
}
