package com.oriah.catalog.product;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Wire-format wrapper for a paginated result.
 *
 * <p>We expose our own envelope rather than serializing Spring Data's
 * {@code Page<T>} directly because Spring's JSON shape is verbose
 * (pageable, sort, numberOfElements, last, first, empty…) and is not
 * stable across major versions. Our envelope keeps a tight, documented
 * contract that the frontend can rely on.
 *
 * @param content       items on this page
 * @param page          zero-based page index
 * @param size          page size requested
 * @param totalElements total number of items across all pages
 * @param totalPages    total number of pages given {@code size}
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    /**
     * Maps a Spring Data {@link Page} into a {@code PageResponse} by applying
     * {@code mapper} to each element. Pure presentation concern.
     */
    public static <S, T> PageResponse<T> from(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
