package com.pfa.backend.dto;

import com.pfa.backend.model.FileStatus;

import java.time.Instant;

public record FileResponse(
        Long id,
        String originalName,
        String storedName,
        String contentType,
        Long size,
        FileStatus status,
        Instant uploadedAt,
        String anonymizedPath
) {}
