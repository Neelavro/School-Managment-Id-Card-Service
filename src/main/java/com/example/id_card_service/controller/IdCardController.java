package com.example.id_card_service.controller;

import com.example.id_card_service.client.AcademicServiceClient;
import com.example.id_card_service.dto.EnrollmentResponseDto;
import com.example.id_card_service.service.IdCardService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/id-card")
public class IdCardController {

    private final AcademicServiceClient academicServiceClient;
    private final IdCardService idCardService;

    public IdCardController(AcademicServiceClient academicServiceClient, IdCardService idCardService) {
        this.academicServiceClient = academicServiceClient;
        this.idCardService = idCardService;
    }

    @GetMapping("/download")
    public void downloadIdCards(
            @RequestParam(required = false) Long academicYearId,
            @RequestParam(required = false) Long shiftId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long genderSectionId,
            @RequestParam(required = false) Long sectionId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Integer fromRoll,
            @RequestParam(required = false) Integer toRoll,
            HttpServletResponse response
    ) throws Exception {

        List<EnrollmentResponseDto> enrollments = academicServiceClient.getEnrollments(
                academicYearId, shiftId, classId, genderSectionId, sectionId, groupId
        );

        if (fromRoll != null || toRoll != null) {
            enrollments = enrollments.stream()
                    .filter(e -> {
                        int roll = e.getClassRoll() != null ? e.getClassRoll() : 0;
                        boolean afterFrom = fromRoll == null || roll >= fromRoll;
                        boolean beforeTo  = toRoll   == null || roll <= toRoll;
                        return afterFrom && beforeTo;
                    })
                    .collect(Collectors.toList());
        }

        enrollments.sort(Comparator.comparingInt(e -> e.getClassRoll() != null ? e.getClassRoll() : 0));

        byte[] pdfBytes = idCardService.generatePdf(enrollments);

        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=student_id_cards.pdf");
        response.getOutputStream().write(pdfBytes);
        response.getOutputStream().flush();
    }

    @GetMapping("/download-back")
    public void downloadIdCardBack(HttpServletResponse response) throws Exception {

        byte[] pdfBytes = idCardService.generateBackPdf();

        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=student_id_card_back.pdf");
        response.getOutputStream().write(pdfBytes);
        response.getOutputStream().flush();
    }
}