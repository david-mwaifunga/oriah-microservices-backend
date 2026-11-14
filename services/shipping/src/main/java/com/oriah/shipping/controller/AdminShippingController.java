package com.oriah.shipping.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.oriah.shipping.dto.AdminRequests.AddOptionRequest;
import com.oriah.shipping.dto.AdminRequests.AddTownRequest;
import com.oriah.shipping.dto.AdminRequests.CreatePriceRequest;
import com.oriah.shipping.dto.AdminRequests.EditPriceRequest;
import com.oriah.shipping.dto.AdminRequests.PublishRequest;
import com.oriah.shipping.dto.AdminRequests.SwitchRequest;
import com.oriah.shipping.dto.AdminRequests.ThresholdRequest;
import com.oriah.shipping.service.DeliveryAdminService;
import com.oriah.shipping.service.DeliveryAdminService.OptionView;
import com.oriah.shipping.service.DeliveryAdminService.PriceView;
import com.oriah.shipping.service.DeliveryAdminService.ProvinceView;
import com.oriah.shipping.service.DeliveryAdminService.SettingsChange;
import com.oriah.shipping.service.DeliveryAdminService.SettingsView;
import com.oriah.shipping.service.DeliveryAdminService.TownView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Delivery prices, kept by admin (Phase 7): towns (switched on and off, never deleted), the options a parcel can go by, a price per town and
 * option (a draft until published, history after) and the free-delivery threshold. SHIPPING_ADMIN only; the class does not need USER, because
 * a job role holds capabilities, not USER. A change to something that exists says the version it was made against: a stale copy is a 409 that
 * names the current version.
 */
@RestController
@RequestMapping("/api/v1/admin/shipping")
@Tag(name = "Delivery prices (admin)", description = "Towns, options, prices and the free-delivery threshold; SHIPPING_ADMIN")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking SHIPPING_ADMIN",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
})
@PreAuthorize("hasRole('SHIPPING_ADMIN')")
public class AdminShippingController {

    private static final String PROBLEMS = "application/problem+json";

    private final DeliveryAdminService admin;

    public AdminShippingController(DeliveryAdminService admin) {
        this.admin = admin;
    }

    @Operation(operationId = "listProvinces", summary = "Zambia's ten provinces, each with how many towns it has")
    @ApiResponse(responseCode = "200", description = "The provinces, each with how many towns it has")
    @GetMapping("/provinces")
    public List<ProvinceView> provinces() {
        return admin.provinces();
    }

    @Operation(operationId = "listTowns", summary = "Towns, optionally of one province or only those switched on or off")
    @ApiResponse(responseCode = "200", description = "The towns")
    @GetMapping("/towns")
    public List<TownView> towns(@RequestParam(name = "provinceId", required = false) UUID provinceId,
                                @RequestParam(name = "active", required = false) Boolean active) {
        return admin.towns(provinceId, active);
    }

    @Operation(operationId = "addTown", summary = "Add a town to a province")
    @ApiResponse(responseCode = "201", description = "The town, switched on")
    @ApiResponse(responseCode = "404", description = "province-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "town-exists", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/towns")
    public ResponseEntity<TownView> addTown(@Valid @RequestBody AddTownRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.addTown(request.provinceId(), request.name(), actor(jwt)));
    }

    @Operation(operationId = "switchTown", summary = "Switch a town on or off (a town is never deleted: orders point at it)")
    @ApiResponse(responseCode = "404", description = "town-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "stale-version (names currentVersion)", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The town, as switched")
    @PatchMapping("/towns/{townId}")
    public TownView switchTown(@PathVariable("townId") UUID townId, @Valid @RequestBody SwitchRequest request, @AuthenticationPrincipal Jwt jwt) {
        return admin.switchTown(townId, request.active(), request.version(), actor(jwt));
    }

    @Operation(operationId = "listOptions", summary = "The options a parcel can go by")
    @ApiResponse(responseCode = "200", description = "The options")
    @GetMapping("/options")
    public List<OptionView> options() {
        return admin.options();
    }

    @Operation(operationId = "addOption", summary = "Add a courier or a bus company's parcel service")
    @ApiResponse(responseCode = "201", description = "The option; its code is made from its name")
    @ApiResponse(responseCode = "409", description = "option-exists", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/options")
    public ResponseEntity<OptionView> addOption(@Valid @RequestBody AddOptionRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.addOption(request.name(), request.kind(), actor(jwt)));
    }

    @Operation(operationId = "switchOption", summary = "Switch an option on or off")
    @ApiResponse(responseCode = "404", description = "option-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "stale-version", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The option, as switched")
    @PatchMapping("/options/{code}")
    public OptionView switchOption(@PathVariable("code") String code, @Valid @RequestBody SwitchRequest request, @AuthenticationPrincipal Jwt jwt) {
        return admin.switchOption(code, request.active(), request.version(), actor(jwt));
    }

    @Operation(operationId = "listPrices", summary = "Prices, drafts and history, optionally of one town, option or status")
    @ApiResponse(responseCode = "200", description = "The prices, drafts and history")
    @GetMapping("/prices")
    public List<PriceView> prices(@RequestParam(name = "townId", required = false) UUID townId,
                                  @RequestParam(name = "optionCode", required = false) String optionCode,
                                  @RequestParam(name = "status", required = false) String status) {
        return admin.prices(townId, optionCode, status);
    }

    @Operation(operationId = "draftPrice", summary = "Draft a price for a town and option (a customer sees nothing until it is published)")
    @ApiResponse(responseCode = "201", description = "The draft")
    @ApiResponse(responseCode = "404", description = "town-not-found, option-not-found", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "draft-exists (names the draft)", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/prices")
    public ResponseEntity<PriceView> draftPrice(@Valid @RequestBody CreatePriceRequest request, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.draftPrice(request.townId(), request.optionCode(), request.amount(),
                !Boolean.FALSE.equals(request.allowsOriahCollects()), Boolean.TRUE.equals(request.allowsRecipientPays()),
                request.etaDaysMin(), request.etaDaysMax(), actor(jwt)));
    }

    @Operation(operationId = "editDraftPrice", summary = "Edit a draft price (a published price cannot be edited: draft a new one)")
    @ApiResponse(responseCode = "409", description = "price-published, stale-version", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The edited draft")
    @PutMapping("/prices/{priceId}")
    public PriceView editDraft(@PathVariable("priceId") UUID priceId, @Valid @RequestBody EditPriceRequest request, @AuthenticationPrincipal Jwt jwt) {
        return admin.editDraft(priceId, request.amount(), !Boolean.FALSE.equals(request.allowsOriahCollects()), Boolean.TRUE.equals(request.allowsRecipientPays()),
                request.etaDaysMin(), request.etaDaysMax(), request.version(), actor(jwt));
    }

    @Operation(operationId = "publishPrice", summary = "Publish a draft: it becomes the live price of its town and option, and the one it replaces becomes history")
    @ApiResponse(responseCode = "409", description = "price-published, stale-version", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The published price")
    @PostMapping("/prices/{priceId}/publish")
    public PriceView publish(@PathVariable("priceId") UUID priceId, @Valid @RequestBody PublishRequest request, @AuthenticationPrincipal Jwt jwt) {
        return admin.publish(priceId, request.version(), actor(jwt));
    }

    @Operation(operationId = "discardDraftPrice", summary = "Discard a draft price (a published price is history and cannot be removed)")
    @ApiResponse(responseCode = "204", description = "Discarded")
    @ApiResponse(responseCode = "409", description = "price-published, stale-version", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @DeleteMapping("/prices/{priceId}")
    public ResponseEntity<Void> discardDraft(@PathVariable("priceId") UUID priceId, @RequestParam(name = "version") long version) {
        admin.discardDraft(priceId, version);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "getDeliverySettings", summary = "The free-delivery threshold (off until set)")
    @ApiResponse(responseCode = "200", description = "The free-delivery threshold")
    @GetMapping("/settings")
    public SettingsView settings() {
        return admin.settings();
    }

    @Operation(operationId = "setFreeDeliveryThreshold", summary = "Set the free-delivery threshold, or switch it off with null; judged on the goods total after discount")
    @ApiResponse(responseCode = "409", description = "stale-version", content = @Content(mediaType = PROBLEMS, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "200", description = "The threshold as set")
    @PutMapping("/settings")
    public SettingsView setThreshold(@Valid @RequestBody ThresholdRequest request, @AuthenticationPrincipal Jwt jwt) {
        return admin.setThreshold(request.freeDeliveryThreshold(), request.version(), request.reason(), actor(jwt));
    }

    @Operation(operationId = "listThresholdChanges", summary = "Every change to the free-delivery threshold, newest first, with who and why")
    @ApiResponse(responseCode = "200", description = "Every change to the threshold, newest first")
    @GetMapping("/settings/changes")
    public List<SettingsChange> changes() {
        return admin.settingsHistory();
    }

    private static String actor(Jwt jwt) {
        return jwt == null || jwt.getSubject() == null ? "system:unknown" : "user:" + jwt.getSubject();
    }
}
