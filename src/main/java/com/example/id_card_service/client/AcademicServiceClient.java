package com.example.id_card_service.client;

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
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<EnrollmentResponseDto> getEnrollments(
            Long academicYearId,
            Long shiftId,
            Long classId,
            Long genderSectionId,
            Long sectionId,
            Long groupId
    ) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL).append("?size=10000");
        if (academicYearId  != null) url.append("&academicYearId=").append(academicYearId);
        if (shiftId         != null) url.append("&shiftId=").append(shiftId);
        if (classId         != null) url.append("&classId=").append(classId);
        if (genderSectionId != null) url.append("&genderSectionId=").append(genderSectionId);
        if (sectionId       != null) url.append("&sectionId=").append(sectionId);
        if (groupId         != null) url.append("&groupId=").append(groupId);

        System.out.println("Calling enrollment API: " + url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Response status: " + response.statusCode());

        JsonNode root = objectMapper.readTree(response.body());
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
}