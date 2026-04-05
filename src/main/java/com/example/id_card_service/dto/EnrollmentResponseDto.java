package com.example.id_card_service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class EnrollmentResponseDto {

    private Long id;
    private Long enrollmentId;
    private String studentSystemId;
    private String nameEnglish;
    private String motherPhone;
    private Integer classRoll;
    private Boolean isActive;

    private AcademicYearDto academicYear;
    private StudentClassDto studentClass;
    private ShiftDto shift;
    private GenderSectionDto genderSection;
    private StudentGroupDto studentGroup;
    private StudentImageDto image;

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AcademicYearDto {
        private Integer id;
        private String yearName;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ShiftDto {
        private Integer id;
        private String name;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GenderSectionDto {
        private Integer id;
        private String genderName;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StudentGroupDto {
        private Integer id;
        private String name;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StudentClassDto {
        private Integer id;
        private String name;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StudentImageDto {
        private Long id;
        private String imageUrl;
        private Boolean isActive;
    }
}