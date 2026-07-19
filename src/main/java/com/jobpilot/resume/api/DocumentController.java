package com.jobpilot.resume.api;

import com.jobpilot.resume.application.ApplicationDocumentSelectionException;
import com.jobpilot.resume.application.ApplicationDocumentSelectionResult;
import com.jobpilot.resume.application.ApplicationDocumentSelectionService;
import com.jobpilot.resume.application.CoverNoteMetadataView;
import com.jobpilot.resume.application.DocumentDownload;
import com.jobpilot.resume.application.ResumeGenerationService;
import com.jobpilot.resume.application.ResumeMetadataView;
import com.jobpilot.resume.domain.DocumentFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.NoSuchElementException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/internal/v1")
public class DocumentController {
    private final ResumeGenerationService generation;
    private final ApplicationDocumentSelectionService selection;

    public DocumentController(ResumeGenerationService generation,
                              ApplicationDocumentSelectionService selection) {
        this.generation = generation;
        this.selection = selection;
    }

    @PostMapping("/jobs/{jobId}/documents")
    public DocumentGenerationResponse generate(@PathVariable @Positive long jobId,
                                               @Valid @RequestBody DocumentGenerationRequest request) {
        return DocumentGenerationResponse.from(generation.generate(jobId, request.toCommand()));
    }

    @GetMapping("/resumes/{resumeVersionId}")
    public ResumeMetadataView resume(@PathVariable @Positive long resumeVersionId) {
        return generation.resume(resumeVersionId);
    }

    @GetMapping("/cover-notes/{coverNoteId}")
    public CoverNoteMetadataView coverNote(@PathVariable @Positive long coverNoteId) {
        return generation.coverNote(coverNoteId);
    }

    @GetMapping("/resumes/{resumeVersionId}/docx")
    public ResponseEntity<byte[]> resumeDocx(@PathVariable @Positive long resumeVersionId) {
        return download(generation.downloadResume(resumeVersionId, DocumentFormat.DOCX));
    }

    @GetMapping("/resumes/{resumeVersionId}/pdf")
    public ResponseEntity<byte[]> resumePdf(@PathVariable @Positive long resumeVersionId) {
        return download(generation.downloadResume(resumeVersionId, DocumentFormat.PDF));
    }

    @GetMapping("/cover-notes/{coverNoteId}/docx")
    public ResponseEntity<byte[]> coverNoteDocx(@PathVariable @Positive long coverNoteId) {
        return download(generation.downloadCoverNote(coverNoteId, DocumentFormat.DOCX));
    }

    @GetMapping("/cover-notes/{coverNoteId}/pdf")
    public ResponseEntity<byte[]> coverNotePdf(@PathVariable @Positive long coverNoteId) {
        return download(generation.downloadCoverNote(coverNoteId, DocumentFormat.PDF));
    }

    @PutMapping("/applications/{jobId}/documents")
    public ApplicationDocumentSelectionResult select(
            @PathVariable @Positive long jobId,
            @Valid @RequestBody ApplicationDocumentSelectionRequest request) {
        return selection.select(jobId, request.resumeVersionId(), request.coverNoteId());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<DocumentApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new DocumentApiError("NOT_FOUND", "Document metadata was not found."));
    }

    @ExceptionHandler(ApplicationDocumentSelectionException.class)
    public ResponseEntity<DocumentApiError> selectionFailure(
            ApplicationDocumentSelectionException failure) {
        HttpStatus status = switch (failure.getCategory()) {
            case APPLICATION_NOT_FOUND, RESUME_NOT_FOUND, COVER_NOTE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(new DocumentApiError(
                failure.getCategory().name(), failure.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class,
            com.jobpilot.resume.storage.ArtifactValidationException.class,
            com.jobpilot.resume.storage.DocumentStorageException.class})
    public ResponseEntity<DocumentApiError> unavailable(RuntimeException failure) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new DocumentApiError("ARTIFACT_UNAVAILABLE",
                        "The requested private artifact is unavailable or invalid."));
    }

    private ResponseEntity<byte[]> download(DocumentDownload artifact) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(artifact.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(artifact.safeFilename(), java.nio.charset.StandardCharsets.US_ASCII)
                .build());
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.setContentLength(artifact.bytes().length);
        return new ResponseEntity<>(artifact.bytes(), headers, HttpStatus.OK);
    }
}
