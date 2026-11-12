package com.oriah.procurement.controller;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.oriah.procurement.dto.DocumentResponse;
import com.oriah.procurement.dto.VoidDocumentRequest;
import com.oriah.procurement.service.DocumentService;
import com.oriah.procurement.service.DocumentService.Owner;
import com.oriah.procurement.service.UploadGate;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Supplier and courier invoices: the evidence a landed cost can be checked against a year later.
 *
 * <p>A file is judged by its own first bytes (PDF, JPEG or PNG, 5 MB at most), kept in the service's
 * own database, and never edited or deleted: a mistaken one is voided with a reason. It is served
 * ONLY as a download, marked not to be sniffed, cached, or made to run anything.
 */
@RestController
@RequestMapping(DocumentController.BASE_PATH)
@PreAuthorize("hasRole('PROCUREMENT_ADMIN')")
@Tag(name = "Documents", description = "Supplier and courier invoices, kept as evidence")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "No or invalid bearer token",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "403", description = "Authenticated, but lacking the procurement capability",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ProblemDetail.class)))
})
public class DocumentController {

    static final String BASE_PATH = "/api/v1/admin/procurement";
    static final String DOCUMENTS = BASE_PATH + "/documents";

    private final DocumentService service;
    private final UploadGate gate;

    public DocumentController(DocumentService service, UploadGate gate) {
        this.service = service;
        this.gate = gate;
    }

    @Operation(operationId = "uploadConsignmentDocument", summary = "Attach an invoice to a consignment",
            description = "The courier's invoice, the supplier's, a ZRA assessment, or anything else that supports the "
                    + "costs. A PDF, JPEG or PNG of up to 5 MB, judged by its own first bytes. The same file is kept once "
                    + "while in use, and at most 10 are in use at a time. Allowed on a received consignment: an invoice "
                    + "often arrives after the goods.")
    @ApiResponse(responseCode = "201", description = "The document was stored; Location points at its download")
    @ApiResponse(responseCode = "400", description = "No file, an unreadable upload, an empty file, one that is not a PDF, JPEG "
            + "or PNG, or a kind that is missing or unknown",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "The file is over 5 MB: refused by the server before it is read "
            + "(the 400 above is for a file that gets past that limit)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The server is busy with other uploads (only a few are held in "
            + "memory at once); nothing was stored. Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The same file is already attached, or 10 are already in use",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/consignments/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadToConsignment(@PathVariable UUID id,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @Parameter(description = "SUPPLIER_INVOICE, COURIER_INVOICE, ZRA_ASSESSMENT or OTHER", required = true)
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "note", required = false) String note) throws Exception {
        // Only a few uploads are held in memory at once: see UploadGate.
        return gate.run(() -> stored(service.upload(Owner.CONSIGNMENT, id, kind, name(file), bytes(file), note, subject())));
    }

    @Operation(operationId = "uploadPurchaseOrderDocument", summary = "Attach an invoice to a purchase order",
            description = "The supplier's invoice, or anything else that supports the order. A courier invoice or a ZRA "
                    + "assessment belongs to a consignment, not an order. A PDF, JPEG or PNG of up to 5 MB.")
    @ApiResponse(responseCode = "201", description = "The document was stored; Location points at its download")
    @ApiResponse(responseCode = "400", description = "No file, an unreadable upload, an empty file, one that is not a PDF, JPEG "
            + "or PNG, or a kind that is missing, unknown, or does not belong to an order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such purchase order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "413", description = "The file is over 5 MB: refused by the server before it is read "
            + "(the 400 above is for a file that gets past that limit)",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The server is busy with other uploads (only a few are held in "
            + "memory at once); nothing was stored. Retry-After says when to try again",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "The same file is already attached, or 10 are already in use",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/purchase-orders/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadToOrder(@PathVariable UUID id,
            @RequestPart(name = "file", required = false) MultipartFile file,
            @Parameter(description = "SUPPLIER_INVOICE or OTHER", required = true)
            @RequestParam(name = "kind", required = false) String kind,
            @RequestParam(name = "note", required = false) String note) throws Exception {
        return gate.run(() -> stored(service.upload(Owner.PURCHASE_ORDER, id, kind, name(file), bytes(file), note, subject())));
    }

    @Operation(operationId = "listConsignmentDocuments", summary = "A consignment's documents, newest first",
            description = "Those in use; with includeVoided, the voided ones too (with who, when and why). The files "
                    + "themselves are downloaded one at a time.")
    @ApiResponse(responseCode = "200", description = "The documents")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID, or includeVoided is not true or false",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such consignment",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/consignments/{id}/documents")
    public List<DocumentResponse> listForConsignment(@PathVariable UUID id,
            @RequestParam(name = "includeVoided", defaultValue = "false") boolean includeVoided) {
        return service.list(Owner.CONSIGNMENT, id, includeVoided);
    }

    @Operation(operationId = "listPurchaseOrderDocuments", summary = "A purchase order's documents, newest first")
    @ApiResponse(responseCode = "200", description = "The documents")
    @ApiResponse(responseCode = "400", description = "The id is not a UUID, or includeVoided is not true or false",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such purchase order",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/purchase-orders/{id}/documents")
    public List<DocumentResponse> listForOrder(@PathVariable UUID id,
            @RequestParam(name = "includeVoided", defaultValue = "false") boolean includeVoided) {
        return service.list(Owner.PURCHASE_ORDER, id, includeVoided);
    }

    @Operation(operationId = "downloadDocument", summary = "Download a document",
            description = "Always an attachment, with the type it really is, marked not to be sniffed or cached, and "
                    + "with a policy that lets it do nothing. Voided documents can still be downloaded.")
    @ApiResponse(responseCode = "200", description = "The file, exactly as it was uploaded",
            content = @Content(mediaType = "application/octet-stream"))
    @ApiResponse(responseCode = "400", description = "The id is not a UUID",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such document",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/documents/{id}/content", produces = MediaType.ALL_VALUE)
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        DocumentService.Download file = service.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noStore())
                .body(file.bytes());
    }

    @Operation(operationId = "voidDocument", summary = "Void a document that should not be relied on",
            description = "It stays on record and downloadable, marked as voided, with who, when and why. There is no "
                    + "delete and no replace. A corrected copy is uploaded as a new document.")
    @ApiResponse(responseCode = "200", description = "The document, now voided")
    @ApiResponse(responseCode = "400", description = "The reason is missing or longer than 200 characters",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "No such document",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "It is already voided",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/documents/{id}/void")
    public DocumentResponse voidDocument(@PathVariable UUID id, @Valid @RequestBody VoidDocumentRequest request) {
        return service.voidDocument(id, request.reason(), subject());
    }

    // ---------------------------------------------------------------- helpers

    private static ResponseEntity<DocumentResponse> stored(DocumentResponse stored) {
        return ResponseEntity.created(URI.create(DOCUMENTS + "/" + stored.id() + "/content")).body(stored);
    }

    private static String name(MultipartFile file) {
        return file == null ? null : file.getOriginalFilename();
    }

    private static byte[] bytes(MultipartFile file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("a file is required: send it as the multipart part called 'file'");
        }
        return file.getBytes();
    }

    private static String subject() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
    }
}
