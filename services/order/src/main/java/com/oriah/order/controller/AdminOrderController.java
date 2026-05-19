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
    public ResponseEntity<OrderResponse> get(@PathVariable("id") UUID id) {
        return service.findById(id)
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Admins can drive any allowed FSM transition (fulfill, ship,
     * deliver). The domain enforces what's reachable from the current
     * state — illegal targets throw IllegalStateException -> 500 today;
     * we'd map to 409 in a polish cycle.
     */
    @PostMapping("/{id}/transition")
    public ResponseEntity<OrderResponse> transition(
            @PathVariable("id") UUID id,
            @Valid @RequestBody TransitionRequest body) {
        return service.transition(id, body.targetStatus())
                .map(OrderResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
