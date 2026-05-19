package com.oriah.search.dto;

import java.util.List;
import java.util.UUID;

import com.oriah.search.model.SearchIndexEntry;

public record SearchResultResponse(String query, int count, List<Hit> hits) {

    public record Hit(UUID variantId, String title, String description, String tags) {
        public static Hit from(SearchIndexEntry e) {
            return new Hit(e.variantId(), e.title(), e.description(), e.tags());
        }
    }
}
