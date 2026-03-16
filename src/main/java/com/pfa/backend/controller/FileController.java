package com.pfa.backend.controller;

import com.pfa.backend.dto.FileResponse;
import com.pfa.backend.model.FileEntity;
import com.pfa.backend.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:8080")
public class FileController {

    private final FileService fileService;

    // ✅ 1) UPLOAD
    @PostMapping("/upload")
    public FileResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        return fileService.upload(file);
    }

    // ✅ 2) LIST ALL
    @GetMapping
    public java.util.List<FileResponse> listAll() {
        return fileService.listAll();
    }

    // ✅ 3) ANONYMIZE
    @PostMapping("/{id}/anonymize")
    public FileResponse anonymize(@PathVariable Long id) throws IOException {
        return fileService.anonymize(id);
    }

    // ✅ 4) DOWNLOAD ORIGINAL
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadOriginal(@PathVariable Long id) {
        Resource resource = fileService.downloadOriginal(id);
        FileEntity file = fileService.getEntityById(id);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition(file.getOriginalName()))
                .body(resource);
    }

    // ✅ 5) DOWNLOAD ANONYMIZED
    @GetMapping("/{id}/download-anonymized")
    public ResponseEntity<Resource> downloadAnonymized(@PathVariable Long id) {
        Resource resource = fileService.downloadAnonymized(id);
        FileEntity file = fileService.getEntityById(id);

        String name = "anonymized_" + file.getOriginalName();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition(name))
                .body(resource);
    }

    // Pour éviter les problèmes d'accents/espaces dans le filename
    private String contentDisposition(String filename) {
        String safe = filename.replace("\"", "");
        return "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(safe, StandardCharsets.UTF_8);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        fileService.delete(id);
        return ResponseEntity.noContent().build();
    }

}

