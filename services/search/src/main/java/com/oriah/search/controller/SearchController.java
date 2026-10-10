package com.oriah.search.controller;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.search.dto.SearchResultResponse;
import com.oriah.search.repository.SearchIndexRepository;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "Full-text product search")
@ApiResponses({
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class SearchController {

    private final SearchIndexRepository repository;

    public SearchController(SearchIndexRepository repository) {
        this.repository = repository;
    }

    @Operation(operationId = "search", summary = "Search products by free text")
    @ApiResponse(responseCode = "200", description = "Matching products, possibly empty")
    @ApiResponse(responseCode = "400", description = "Validation failure or unparseable parameter; see fieldErrors",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
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
