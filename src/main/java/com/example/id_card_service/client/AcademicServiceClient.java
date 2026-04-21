package com.example.id_card_service.client;

import com.example.id_card_service.dto.AdmitCardBySectionRoutineResponseDto;
import com.example.id_card_service.dto.AdmitCardRoutineResponseDto;
import com.example.id_card_service.dto.EnrollmentResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Component
public class AcademicServiceClient {

    private static final String BASE_URL = "http://167.172.86.59:8084/api/enrollments";
//    private static final String BASE_URL = "http://192.168.0.187:8084/api/enrollments";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    public List<EnrollmentResponseDto> getEnrollments(
            Long academicYearId,
            Long shiftId,
            Long classId,
            Long genderSectionId,
            Long sectionId,
            Long groupId,
            Integer startRoll,  // ← add
            Integer endRoll     // ← add
    ) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL).append("?size=10000");
        if (academicYearId  != null) url.append("&academicYearId=").append(academicYearId);
        if (shiftId         != null) url.append("&shiftId=").append(shiftId);
        if (classId         != null) url.append("&classId=").append(classId);
        if (genderSectionId != null) url.append("&genderSectionId=").append(genderSectionId);
        if (sectionId       != null) url.append("&sectionId=").append(sectionId);
        if (groupId != null) url.append("&studentGroupId=").append(groupId);  // was &groupId=
        if (startRoll       != null) url.append("&startRoll=").append(startRoll);  // ← add
        if (endRoll         != null) url.append("&endRoll=").append(endRoll);      // ← add

        System.out.println("Calling enrollment API: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        System.out.println("Response status: " + response.statusCode());

        JsonNode root    = objectMapper.readTree(response.body());
        JsonNode content = root.path("content");

        List<EnrollmentResponseDto> list = new ArrayList<>();
        if (content.isArray()) {
            System.out.println("Found " + content.size() + " enrollments");
            for (JsonNode node : content) {
                list.add(objectMapper.treeToValue(node, EnrollmentResponseDto.class));
            }
        } else {
            System.out.println("Content is not an array: " + content.getNodeType());
        }

        return list;
    }

    private static final String ADMIT_CARD_URL = "http://167.172.86.59:8084/api/admit-card";
//    private static final String ADMIT_CARD_URL = "http://192.168.0.187:8084/api/admit-card";

    public AdmitCardRoutineResponseDto getAdmitCardData(
            Integer routineId,
            Integer sessionId,
            Integer classId,
            Integer genderSectionId,
            Long sectionId
    ) throws Exception {
        StringBuilder url = new StringBuilder(ADMIT_CARD_URL);
        url.append("?routineId=").append(routineId);
        if (sessionId       != null) url.append("&sessionId=").append(sessionId);
        if (classId         != null) url.append("&classId=").append(classId);
        if (genderSectionId != null) url.append("&genderSectionId=").append(genderSectionId);
        if (sectionId       != null) url.append("&sectionId=").append(sectionId);

        System.out.println("Calling admit card API: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        System.out.println("Response status: " + response.statusCode());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Admit card API failed: " + response.body());
        }

        return objectMapper.readValue(response.body(), AdmitCardRoutineResponseDto.class);
    }

    //wihtout room , only class, gender section and section

    private static final String ADMIT_CARD_BY_SECTION_URL =
            "http://167.172.86.59:8084/api/admit-card/by-section";
//  "http://192.168.0.187:8084/api/admit-card/by-section";

    public AdmitCardBySectionRoutineResponseDto getAdmitCardDataBySection(
            Integer routineId,
            Integer sessionId,
            Integer classId,
            Integer genderSectionId,
            Long sectionId,
            Integer groupId            // ← add
    ) throws Exception {
        StringBuilder url = new StringBuilder(ADMIT_CARD_BY_SECTION_URL);
        url.append("?routineId=").append(routineId);
        if (sessionId       != null) url.append("&sessionId=").append(sessionId);
        if (classId         != null) url.append("&classId=").append(classId);
        if (genderSectionId != null) url.append("&genderSectionId=").append(genderSectionId);
        if (sectionId       != null) url.append("&sectionId=").append(sectionId);
        if (groupId         != null) url.append("&groupId=").append(groupId);  // ← add

        System.out.println("Calling admit card by-section API: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        System.out.println("Response status: " + response.statusCode());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Admit card by-section API failed: " + response.body());
        }

        return objectMapper.readValue(response.body(),
                AdmitCardBySectionRoutineResponseDto.class);
    }
}