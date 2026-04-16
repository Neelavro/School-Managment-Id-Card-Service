package com.example.id_card_service.controller;

import com.example.id_card_service.client.AcademicServiceClient;
import com.example.id_card_service.dto.EnrollmentResponseDto;
import com.example.id_card_service.service.SeatPlanService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seat-plan")
public class SeatPlanController {

    private final SeatPlanService seatPlanService;
    private final AcademicServiceClient academicServiceClient;

    public SeatPlanController(SeatPlanService seatPlanService,
                              AcademicServiceClient academicServiceClient) {
        this.seatPlanService = seatPlanService;
        this.academicServiceClient = academicServiceClient;
    }

    @GetMapping("/generate")
    public ResponseEntity<byte[]> generateSeatPlan(
            @RequestParam String examName,
            @RequestParam(required = false) Long academicYearId,
            @RequestParam(required = false) Long shiftId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long genderSectionId,
            @RequestParam(required = false) Long sectionId,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) Integer startRoll,
            @RequestParam(required = false) Integer endRoll
    ) throws Exception {

        List<EnrollmentResponseDto> enrollments = academicServiceClient.getEnrollments(
                academicYearId,
                shiftId,
                classId,
                genderSectionId,
                sectionId,
                groupId,
                startRoll,
                endRoll
        );

        if (enrollments == null || enrollments.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        byte[] pdf = seatPlanService.generatePdf(enrollments, examName);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"seat-plan.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}