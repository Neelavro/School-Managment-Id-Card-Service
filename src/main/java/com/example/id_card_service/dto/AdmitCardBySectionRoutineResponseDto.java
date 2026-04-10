package com.example.id_card_service.dto;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdmitCardBySectionRoutineResponseDto {

    private Integer id;
    private String  title;
    private String  examTypeName;
    private String  academicYearName;
    private String  status;
    private Boolean isActive;

    private List<SessionDto> sessions;

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionDto {
        private Integer id;
        private String  subjectName;
        private String  date;
        private String  startTime;
        private String  endTime;
        private String  className;
        private Integer groupId;       // ← add
        private List<AllocationDto> allocations;
        private List<SessionDto>    fullSchedule;
    }
    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AllocationDto {
        // No roomId / roomName / startRoll / endRoll
        private String sectionName;
        private String genderSectionName;
        private List<StudentDto> students;
    }

    @Getter @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StudentDto {
        private String  studentSystemId;
        private Integer classRoll;
    }
}