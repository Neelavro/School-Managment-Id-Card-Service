package com.example.id_card_service.controller;

import com.example.id_card_service.service.AdmitCardPdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admit-card")
@RequiredArgsConstructor
public class AdmitCardController {

    private final AdmitCardPdfService admitCardPdfService;

    @GetMapping(produces = "application/pdf")
    public ResponseEntity<byte[]> generateAdmitCards(
            @RequestParam Integer routineId,
            @RequestParam(required = false) Integer sessionId,
            @RequestParam(required = false) Integer classId,
            @RequestParam(required = false) Integer genderSectionId,
            @RequestParam(required = false) Long sectionId
    ) {
        try {
            byte[] pdf = admitCardPdfService.generateAdmitCards(
                    routineId, sessionId, classId, genderSectionId, sectionId);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"admit-cards.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdf);

        } catch (Exception e) {
            System.err.println("Controller error: " + e.getMessage());
            e.printStackTrace();

            return ResponseEntity.internalServerError().build();
        }
    }
}