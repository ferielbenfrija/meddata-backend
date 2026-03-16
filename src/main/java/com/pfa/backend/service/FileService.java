package com.pfa.backend.service;

import com.pfa.backend.dto.FileResponse;
import com.pfa.backend.model.FileEntity;
import com.pfa.backend.model.FileStatus;
import com.pfa.backend.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;

    private final Path uploadDir = Paths.get("uploads");
    private final Path anonymizedDir = uploadDir.resolve("anonymized");

    // =========================================
    // UPLOAD
    // =========================================
    public FileResponse upload(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        Files.createDirectories(uploadDir);

        String originalName = Objects.requireNonNullElse(file.getOriginalFilename(), "file");
        String storedName = UUID.randomUUID() + "_" + originalName;

        Path target = uploadDir.resolve(storedName);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);

        FileEntity entity = FileEntity.builder()
                .originalName(originalName)
                .storedName(storedName)
                .contentType(
                        file.getContentType() == null ?
                                "application/octet-stream" :
                                file.getContentType()
                )
                .size(file.getSize())
                .status(FileStatus.PENDING)
                .uploadedAt(Instant.now())
                .build();

        return toResponse(fileRepository.save(entity));
    }

    // =========================================
    // LIST
    // =========================================
    public List<FileResponse> listAll() {
        return fileRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public FileEntity getEntityById(Long id) {
        return fileRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("File not found: " + id));
    }

    // =========================================
    // ANONYMIZE
    // =========================================
    public FileResponse anonymize(Long id) throws IOException {

        FileEntity file = getEntityById(id);

        if (file.getStatus() == FileStatus.ANONYMIZED) {
            return toResponse(file);
        }

        Files.createDirectories(anonymizedDir);

        Path originalPath = uploadDir.resolve(file.getStoredName());

        if (Files.notExists(originalPath)) {
            throw new RuntimeException("Original file not found on disk");
        }

        String anonymizedFileName = "anonymized_" + file.getStoredName();
        Path anonymizedPath = anonymizedDir.resolve(anonymizedFileName);

        // 🔥 SI CSV → anonymisation réelle
        if (file.getOriginalName().toLowerCase().endsWith(".csv")) {
            anonymizeCsv(originalPath, anonymizedPath);
        } else {
            // sinon copie simple
            Files.copy(originalPath, anonymizedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        file.setStatus(FileStatus.ANONYMIZED);
        file.setAnonymizedPath(anonymizedPath.toString());

        return toResponse(fileRepository.save(file));
    }

    // =========================================
    // CSV ANONYMIZATION (Medical rules)
    // =========================================
    private void anonymizeCsv(Path input, Path output) throws IOException {

        try (
                Reader reader = Files.newBufferedReader(input);
                CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader());
                BufferedWriter writer = Files.newBufferedWriter(output);
                CSVPrinter printer = new CSVPrinter(writer,
                        CSVFormat.DEFAULT.withHeader(
                                "PATIENT_ID",
                                "AGE_TRANCHE",
                                "SEXE",
                                "REGION",
                                "DATE_ADMISSION_MOIS_ANNEE",
                                "DIAGNOSTIC_ICD10",
                                "MEDECIN_TRAITANT",
                                "PRIX_CONSULTATION",
                                "RESULTATS_LABO"
                        ))
        ) {

            DateTimeFormatter birthFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            DateTimeFormatter admissionFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");

            for (CSVRecord record : parser) {

                String uuid = UUID.randomUUID().toString();

                // AGE_TRANCHE
                String ageTranche = "";
                try {
                    LocalDate birth = LocalDate.parse(record.get("DATE_NAISSANCE"), birthFormatter);
                    int age = Period.between(birth, LocalDate.now()).getYears();
                    int lower = (age / 10) * 10;
                    ageTranche = lower + "-" + (lower + 9);
                } catch (Exception ignored) {}

                // REGION (example simple)
                String region = record.get("CODE_POSTAL").substring(0, 2);

                // DATE_ADMISSION_MOIS_ANNEE
                String admission = "";
                try {
                    LocalDate date = LocalDate.parse(record.get("DATE_ADMISSION"), admissionFormatter);
                    admission = date.getMonthValue() + "/" + date.getYear();
                } catch (Exception ignored) {}

                printer.printRecord(
                        uuid,
                        ageTranche,
                        record.get("SEXE"),
                        region,
                        admission,
                        record.get("DIAGNOSTIC_ICD10"),
                        record.get("MEDECIN_TRAITANT"),
                        record.get("PRIX_CONSULTATION"),
                        record.get("RESULTATS_LABO")
                );
            }
        }
    }

    // =========================================
    // DOWNLOAD
    // =========================================
    public Resource downloadOriginal(Long id) {
        FileEntity file = getEntityById(id);
        return toResource(uploadDir.resolve(file.getStoredName()));
    }

    public Resource downloadAnonymized(Long id) {
        FileEntity file = getEntityById(id);

        if (file.getStatus() != FileStatus.ANONYMIZED) {
            throw new RuntimeException("File not anonymized yet");
        }

        return toResource(Paths.get(file.getAnonymizedPath()));
    }

    private Resource toResource(Path path) {
        try {
            if (Files.notExists(path)) {
                throw new RuntimeException("File not found on disk");
            }

            Resource resource = new UrlResource(path.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                throw new RuntimeException("File not readable");
            }

            return resource;

        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid file path", e);
        }
    }

    // =========================================
    // DELETE
    // =========================================
    public void delete(Long id) {

        FileEntity file = getEntityById(id);

        try {
            Files.deleteIfExists(uploadDir.resolve(file.getStoredName()));
            if (file.getAnonymizedPath() != null) {
                Files.deleteIfExists(Paths.get(file.getAnonymizedPath()));
            }
        } catch (IOException ignored) {}

        fileRepository.delete(file);
    }

    // =========================================
    // MAPPER
    // =========================================
    private FileResponse toResponse(FileEntity e) {
        return new FileResponse(
                e.getId(),
                e.getOriginalName(),
                e.getStoredName(),
                e.getContentType(),
                e.getSize(),
                e.getStatus(),
                e.getUploadedAt(),
                e.getAnonymizedPath()
        );
    }
}
