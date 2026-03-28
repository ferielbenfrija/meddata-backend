package com.pfa.backend.service;

import com.pfa.backend.dto.FileResponse;
import com.pfa.backend.model.FileEntity;
import com.pfa.backend.model.FileStatus;
import com.pfa.backend.repository.FileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.MalformedURLException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileRepository fileRepository;

    @Value("${tesseract.datapath:}")
    private String tesseractDataPath;

    private final Path uploadDir = Paths.get("uploads");
    private final Path anonymizedDir = uploadDir.resolve("anonymized");

    // Patterns pour détecter les informations sensibles
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "\\d{1,2}-(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-\\d{4}|" +
                    "\\d{1,2}/\\d{1,2}/\\d{4}|" +
                    "\\d{4}-\\d{2}-\\d{2}"
    );

    private static final Pattern ID_PATTERN = Pattern.compile(
            "[A-Z0-9]{4,}-[A-Z0-9]{4,}-[A-Z0-9]{4,}|" +
                    "\\d{8,}|" +
                    "[A-Z]{2}\\d{6,}"
    );

    private static final Pattern HOSPITAL_PATTERN = Pattern.compile(
            "(?i)(hopital|hôpital|clinic|center|centre|hospital|MRI|CT|scanner|radiologie)"
    );

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
                .contentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType())
                .size(file.getSize())
                .status(FileStatus.PENDING)
                .uploadedAt(Instant.now())
                .anonymizedPath(null)
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

    public List<FileResponse> listByStatus(FileStatus status) {
        return fileRepository.findByStatus(status)
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

        String lowerName = file.getOriginalName().toLowerCase();

        if (lowerName.endsWith(".csv")) {
            anonymizeCsv(originalPath, anonymizedPath);
        } else if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            anonymizeMedicalImageWithOcr(originalPath, anonymizedPath);
        } else {
            Files.copy(originalPath, anonymizedPath, StandardCopyOption.REPLACE_EXISTING);
        }

        file.setStatus(FileStatus.ANONYMIZED);
        file.setAnonymizedPath(anonymizedPath.toString());

        return toResponse(fileRepository.save(file));
    }

    // =========================================
    // CSV ANONYMIZATION
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

                String ageTranche = "";
                try {
                    LocalDate birth = LocalDate.parse(record.get("DATE_NAISSANCE"), birthFormatter);
                    int age = Period.between(birth, LocalDate.now()).getYears();
                    int lower = (age / 10) * 10;
                    ageTranche = lower + "-" + (lower + 9);
                } catch (Exception ignored) {
                }

                String region = "";
                try {
                    String cp = record.get("CODE_POSTAL").trim();
                    region = cp.length() >= 2 ? cp.substring(0, 2) : cp;
                } catch (Exception ignored) {
                }

                String admission = "";
                try {
                    String rawDate = record.get("DATE_ADMISSION").trim();
                    LocalDate date = LocalDate.parse(rawDate, admissionFormatter);
                    admission = String.format("%02d/%d", date.getMonthValue(), date.getYear());
                } catch (Exception ignored) {
                }

                printer.printRecord(
                        uuid,
                        ageTranche,
                        safeGet(record, "SEXE"),
                        region,
                        admission,
                        safeGet(record, "DIAGNOSTIC_ICD10"),
                        safeGet(record, "MEDECIN_TRAITANT"),
                        safeGet(record, "PRIX_CONSULTATION"),
                        safeGet(record, "RESULTATS_LABO")
                );
            }
        }
    }

    private String safeGet(CSVRecord record, String col) {
        try {
            return record.get(col);
        } catch (Exception e) {
            return "";
        }
    }

    // =========================================
    // IMAGE OCR ANONYMIZATION - VERSION AMÉLIORÉE
    // =========================================
    private void anonymizeMedicalImageWithOcr(Path input, Path output) throws IOException {
        BufferedImage image = ImageIO.read(input.toFile());

        if (image == null) {
            throw new IOException("Impossible de lire l'image");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Créer une copie pour l'anonymisation
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = result.createGraphics();
        g2d.drawImage(image, 0, 0, null);
        g2d.setColor(Color.BLACK);

        // Variables pour suivre l'activité OCR
        int wordsDetected = 0;
        int sensitiveWordsMasked = 0;

        // =========================================
        // 1. TENTATIVE OCR
        // =========================================
        try {
            ITesseract tesseract = new Tesseract();

            // Configurer le chemin si fourni
            if (tesseractDataPath != null && !tesseractDataPath.isEmpty()) {
                tesseract.setDatapath(tesseractDataPath);
            }

            tesseract.setLanguage("fra+eng"); // Français et anglais
            tesseract.setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO);

            // Récupérer tous les mots détectés
            List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            wordsDetected = words.size();

            for (Word word : words) {
                String text = word.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }

                // Vérifier si c'est une information sensible
                if (isSensitiveText(text)) {
                    Rectangle rect = word.getBoundingBox();

                    // Ajouter du padding pour bien masquer
                    int padding = 8;
                    int x = Math.max(0, rect.x - padding);
                    int y = Math.max(0, rect.y - padding);
                    int w = Math.min(width - x, rect.width + padding * 2);
                    int h = Math.min(height - y, rect.height + padding * 2);

                    g2d.fillRect(x, y, w, h);
                    sensitiveWordsMasked++;
                    log.debug("Texte masqué: '{}' à ({},{})", text, rect.x, rect.y);
                }
            }

            log.info("OCR: {} mots détectés, {} masqués", wordsDetected, sensitiveWordsMasked);

        } catch (Exception e) {
            log.error("Erreur OCR: {}", e.getMessage());
            log.warn("Utilisation du fallback (masques fixes)");

            // FALLBACK: masquer les zones par défaut
            g2d.fillRect(0, 0, width, Math.min(140, height / 4));      // Bande haute
            g2d.fillRect(0, height - 70, width, 70);                    // Bande basse
            g2d.fillRect(0, 0, 90, height);                             // Bande gauche
            g2d.fillRect(width - 100, 0, 100, height);                  // Bande droite
        }

        // =========================================
        // 2. MASQUES DE SÉCURITÉ (toujours appliqués)
        // =========================================
        // Bande haute sécurité (30px) - capture les petites annotations
        g2d.fillRect(0, 0, width, 35);

        // Bande basse sécurité (25px)
        g2d.fillRect(0, height - 30, width, 30);

        g2d.dispose();

        String lowerName = input.getFileName().toString().toLowerCase();
        String format = lowerName.endsWith(".png") ? "png" : "jpg";
        ImageIO.write(result, format, output.toFile());

        log.info("Image anonymisée sauvegardée: {}", output.getFileName());
    }

    /**
     * Détecte si un texte contient des informations sensibles
     */
    private boolean isSensitiveText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String lower = text.toLowerCase().trim();

        // 1. Mots-clés sensibles (médicaux et identifiants)
        String[] sensitiveKeywords = {
                "patient", "nom", "prenom", "name", "date", "birth", "naissance",
                "id", "identifier", "nss", "secu", "social", "medecin", "doctor",
                "hopital", "hospital", "clinic", "centre", "radiologie", "scanner",
                "irm", "mri", "ct", "pet", "spect", "mammo", "echo", "ultrasound",
                "dicom", "series", "study", "accession", "institution"
        };

        for (String keyword : sensitiveKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }

        // 2. Patterns de dates
        if (DATE_PATTERN.matcher(text).find()) {
            return true;
        }

        // 3. Patterns d'ID (NSS, ID patient, etc.)
        if (ID_PATTERN.matcher(text).find()) {
            return true;
        }

        // 4. Présence de chiffres (souvent des IDs)
        if (text.matches(".*\\d.*") && text.length() > 4) {
            // Vérifier si c'est un code médical (CIM-10 par exemple)
            if (!text.matches("[A-Z]\\d{2}\\.\\d")) {
                return true;
            }
        }

        // 5. Combinaison lettre/chiffre avec tiret (IDs)
        if (text.matches("[A-Z0-9-]{6,}")) {
            return true;
        }

        return false;
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

        if (file.getStatus() != FileStatus.ANONYMIZED || file.getAnonymizedPath() == null) {
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
        } catch (IOException ignored) {
        }

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