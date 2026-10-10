package com.oriah.order.controller;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.order.dto.OrderResponse;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.dto.TransitionRequest;
import com.oriah.order.service.OrderService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasRole('ORDER_ADMIN')")
public class AdminOrderController {

    private final OrderService service;

    public AdminOrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Page<OrderResponse>> list(@PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(service.findAll(pageable).map(OrderResponse::from));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable("id") UUID id) {
        return service.findById(id)
                .map(OrderResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id.toString()));
    }

    /**
     * Admins can drive any allowed FSM transition (fulfill, ship,
     * deliver). The domain enforces what is reachable from the current state.
     *
     * <p>This used to say illegal targets "throw IllegalStateException -> 500
     * today; we'd map to 409 in a polish cycle". That comment outlived its
     * truth: the shared advice has mapped {@code IllegalStateException} to
     * <strong>409 Conflict</strong> since Tier 0.5, so an illegal transition
     * already answers correctly. Corrected rather than deleted, because a
     * comment claiming a 500 would send the next reader looking for a bug that
     * is not there.
     */
    @PostMapping("/{id}/transition")
    public OrderResponse transition(
            @PathVariable("id") UUID id,
            @Valid @RequestBody TransitionRequest body) {
        return service.transition(id, body.targetStatus())
                .map(OrderResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id.toString()));
    }
}
