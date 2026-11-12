package com.oriah.search.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.search.model.SearchIndexEntry;

public interface SearchIndexRepository extends JpaRepository<SearchIndexEntry, UUID> {

    /**
     * Postgres full-text search using websearch_to_tsquery (which
     * accepts user-friendly queries like {@code "rings OR necklaces"}
     * or {@code "aurelia -ring"}). ts_rank gives relevance ordering.
     *
     * <p>Only rows that are {@code listed} -- the product is ACTIVE and
     * SELLABLE. The index keeps a row for every product it has been told of,
     * drafts and packaging included, so a search that forgot this condition
     * would show an unfinished piece.
     *
     * <p>Limit is hardcoded for the demo; real impl uses Pageable.
     */
    @Query(value = """
            SELECT * FROM search_index
            WHERE listed AND document @@ websearch_to_tsquery('english', :q)
            ORDER BY ts_rank(document, websearch_to_tsquery('english', :q)) DESC, updated_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchIndexEntry> search(@Param("q") String q, @Param("limit") int limit);
}
