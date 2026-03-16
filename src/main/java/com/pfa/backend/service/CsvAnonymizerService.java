package com.pfa.backend.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class CsvAnonymizerService {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE // yyyy-MM-dd
    );

    // Nettoyage basique dans texte libre
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern PHONE =
            java.util.regex.Pattern.compile("(\\+?\\d[\\d\\s().-]{7,}\\d)");

    public void anonymizeCsv(Path inputCsv, Path outputCsv) throws IOException {
        // mapping stable dans le fichier : PATIENT_ID -> UUID
        Map<String, String> patientMap = new HashMap<>();

        try (Reader reader = Files.newBufferedReader(inputCsv, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT
                     .builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .setTrim(true)
                     .build()
                     .parse(reader);
             BufferedWriter writer = Files.newBufferedWriter(outputCsv, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader(
                             "PATIENT_ID",
                             "AGE_TRANCHE",
                             "SEXE",
                             "REGION",
                             "DATE_ADMISSION_MOIS_ANNEE",
                             "DIAGNOSTIC_ICD10",
                             "MEDECIN_TRAITANT",
                             "PRIX_CONSULTATION",
                             "RESULTATS_LABO"
                     )
                     .build())
        ) {
            for (CSVRecord r : parser) {
                String patientId = get(r, "PATIENT_ID");
                String uuid = patientMap.computeIfAbsent(
                        safe(patientId),
                        k -> UUID.randomUUID().toString()
                );

                String dob = get(r, "DATE_NAISSANCE");
                String ageTranche = toAgeTranche(dob);

                String sexe = get(r, "SEXE");

                String cp = get(r, "CODE_POSTAL");
                String region = toRegion(cp);

                String admission = get(r, "DATE_ADMISSION");
                String admissionMY = toMonthYear(admission);

                String icd10 = get(r, "DIAGNOSTIC_ICD10");
                String medecin = get(r, "MEDECIN_TRAITANT");

                String prix = get(r, "PRIX_CONSULTATION");
                String prixRounded = roundPrice(prix);

                String labo = get(r, "RESULTATS_LABO");
                String laboClean = cleanFreeText(labo);

                printer.printRecord(
                        uuid,
                        ageTranche,
                        safe(sexe),
                        region,
                        admissionMY,
                        safe(icd10),
                        safe(medecin),
                        prixRounded,
                        laboClean
                );
            }
            printer.flush();
        }
    }

    private String get(CSVRecord r, String col) {
        try {
            return r.isMapped(col) ? r.get(col) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private String toAgeTranche(String dateStr) {
        LocalDate dob = parseDate(dateStr);
        if (dob == null) return "";

        int age = Period.between(dob, LocalDate.now()).getYears();
        if (age < 0) return ""; // date future bizarre

        if (age <= 9) return "0-9";
        if (age <= 19) return "10-19";
        if (age <= 29) return "20-29";
        if (age <= 39) return "30-39";
        if (age <= 49) return "40-49";
        if (age <= 59) return "50-59";
        if (age <= 69) return "60-69";
        if (age <= 79) return "70-79";
        return "80+";
    }

    private String toMonthYear(String dateStr) {
        LocalDate d = parseDate(dateStr);
        if (d == null) return "";
        String mm = String.format("%02d", d.getMonthValue());
        return mm + "/" + d.getYear();
    }

    private LocalDate parseDate(String dateStr) {
        String s = safe(dateStr);
        if (s.isBlank()) return null;

        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    // REGION: version simple = 2 premiers chiffres du code postal (ex: 75001 -> 75)
    // Tu peux remplacer plus tard par une vraie table CP->Région.
    private String toRegion(String codePostal) {
        String cp = safe(codePostal).replaceAll("\\s+", "");
        if (cp.isBlank()) return "";

        // garde seulement chiffres
        cp = cp.replaceAll("[^0-9]", "");
        if (cp.length() >= 2) return cp.substring(0, 2);
        if (cp.length() == 1) return cp;
        return "";
    }

    private String roundPrice(String prix) {
        String s = safe(prix).replace(",", ".");
        if (s.isBlank()) return "";

        try {
            double v = Double.parseDouble(s);
            long rounded = Math.round(v);
            return String.valueOf(rounded);
        } catch (NumberFormatException e) {
            return s; // si c'est pas un nombre, on le laisse tel quel
        }
    }

    private String cleanFreeText(String txt) {
        String s = safe(txt);
        if (s.isBlank()) return "";
        s = EMAIL.matcher(s).replaceAll("[EMAIL]");
        s = PHONE.matcher(s).replaceAll("[PHONE]");
        return s;
    }
}
