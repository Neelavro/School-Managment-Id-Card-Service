package com.example.id_card_service.service;

import com.example.id_card_service.client.AcademicServiceClient;
import com.example.id_card_service.dto.AdmitCardRoutineResponseDto;
import com.example.id_card_service.dto.AdmitCardRoutineResponseDto.AllocationDto;
import com.example.id_card_service.dto.AdmitCardRoutineResponseDto.SessionDto;
import com.example.id_card_service.dto.AdmitCardRoutineResponseDto.StudentDto;
import com.example.id_card_service.dto.EnrollmentResponseDto;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdmitCardPdfService {

    private static final int PAGE_W = 794;
    private static final int PAGE_H = 1123;

    private final AcademicServiceClient academicServiceClient;
    private final IdCardService         idCardService;

    private String logoBase64;
    private String signatureBase64;

    public AdmitCardPdfService(AcademicServiceClient academicServiceClient,
                               IdCardService idCardService) {
        this.academicServiceClient = academicServiceClient;
        this.idCardService         = idCardService;
    }

    @PostConstruct
    public void init() {
        try {
            InputStream is = getClass().getResourceAsStream("/static/logo.png");
            logoBase64 = "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) {
            logoBase64 = "";
            System.err.println("Warning: Could not load logo. " + e.getMessage());
        }
        try {
            InputStream is = getClass().getResourceAsStream("/static/signature.png");
            signatureBase64 = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) {
            signatureBase64 = "";
            System.err.println("Warning: Could not load signature. " + e.getMessage());
        }
    }

    public byte[] generateAdmitCards(
            Integer routineId,
            Integer sessionId,
            Integer classId,
            Integer genderSectionId,
            Long sectionId
    ) throws Exception {
        try {
            // Step 1: Get routine + sessions + allocations + students
            AdmitCardRoutineResponseDto routine = academicServiceClient.getAdmitCardData(
                    routineId, sessionId, classId, genderSectionId, sectionId);

            System.out.println("Routine: " + routine.getTitle());
            System.out.println("Sessions: " + (routine.getSessions() != null
                    ? routine.getSessions().size() : "NULL"));

            // Step 2: Collect all unique studentSystemIds
            Set<String> allSystemIds = new LinkedHashSet<>();
            for (SessionDto session : routine.getSessions()) {
                for (AllocationDto alloc : session.getAllocations()) {
                    for (StudentDto student : alloc.getStudents()) {
                        if (student.getStudentSystemId() != null)
                            allSystemIds.add(student.getStudentSystemId());
                    }
                }
            }
            System.out.println("System IDs: " + allSystemIds);

            // Step 3: Fetch full enrollment info
            List<EnrollmentResponseDto> allEnrollments = academicServiceClient.getEnrollments(
                    null, null,
                    classId != null ? classId.longValue() : null,
                    genderSectionId != null ? genderSectionId.longValue() : null,
                    sectionId, null, null, null);

            System.out.println("Enrollments fetched: " + allEnrollments.size());
            allEnrollments.forEach(e -> System.out.println(
                    "  systemId=" + e.getStudentSystemId()
                            + " name=" + e.getNameEnglish()
                            + " phone=" + e.getMotherPhone()));

            Map<String, EnrollmentResponseDto> enrollmentMap = allEnrollments.stream()
                    .filter(e -> e.getStudentSystemId() != null)
                    .collect(Collectors.toMap(
                            EnrollmentResponseDto::getStudentSystemId,
                            e -> e,
                            (a, b) -> a));

            System.out.println("Enrollment map size: " + enrollmentMap.size());

            // Step 4: Build per-student admit card data
            Map<String, StudentAdmitData> studentDataMap = new LinkedHashMap<>();
            for (SessionDto session : routine.getSessions()) {
                for (AllocationDto alloc : session.getAllocations()) {
                    for (StudentDto student : alloc.getStudents()) {
                        String sid = student.getStudentSystemId();
                        if (sid == null) continue;
                        studentDataMap.computeIfAbsent(sid, k -> new StudentAdmitData(
                                sid,
                                student.getClassRoll(),
                                alloc.getRoomName(),
                                alloc.getSectionName(),
                                alloc.getGenderSectionName(),
                                session.getClassName()
                        )).addSession(session);
                    }
                }
            }

            System.out.println("Student data map size: " + studentDataMap.size());
            studentDataMap.forEach((k, v) -> {
                EnrollmentResponseDto e = enrollmentMap.get(k);
                System.out.println("  " + k + " -> found=" + (e != null)
                        + " name=" + (e != null ? e.getNameEnglish() : "MISSING")
                        + " phone=" + (e != null ? e.getMotherPhone() : "MISSING"));
            });

            // Step 4b: Replace each student's sessions with full class schedule
            for (StudentAdmitData data : studentDataMap.values()) {
                if (!data.sessions.isEmpty()) {
                    List<SessionDto> fullSchedule =
                            data.sessions.get(0).getFullSchedule();
                    if (fullSchedule != null && !fullSchedule.isEmpty()) {
                        data.sessions = new ArrayList<>(fullSchedule);
                        data.sessions.sort(Comparator.comparing(SessionDto::getDate));
                    }
                }
            }

            // Step 5: Generate HTML and PDF
            String html = buildHtml(routine, studentDataMap, enrollmentMap);
            System.out.println("HTML length: " + html.length());

            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setHeadless(true));
                Page page = browser.newPage();
                page.setContent(html, new Page.SetContentOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));
                byte[] pdf = page.pdf(new Page.PdfOptions()
                        .setPrintBackground(true)
                        .setMargin(new Margin().setTop("0").setBottom("0")
                                .setLeft("0").setRight("0"))
                        .setWidth(PAGE_W + "px")
                        .setHeight(PAGE_H + "px"));
                System.out.println("PDF size: " + pdf.length + " bytes");
                browser.close();
                return pdf;
            }

        } catch (Exception e) {
            System.err.println("ERROR in generateAdmitCards: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    // ── Inner data holder ─────────────────────────────────────────────────────

    static class StudentAdmitData {
        String studentSystemId;
        Integer classRoll;
        String roomName;
        String sectionName;
        String genderSectionName;
        String className;
        List<SessionDto> sessions = new ArrayList<>();

        StudentAdmitData(String studentSystemId, Integer classRoll,
                         String roomName, String sectionName,
                         String genderSectionName, String className) {
            this.studentSystemId   = studentSystemId;
            this.classRoll         = classRoll;
            this.roomName          = roomName;
            this.sectionName       = sectionName;
            this.genderSectionName = genderSectionName;
            this.className         = className;
        }

        void addSession(SessionDto s) { sessions.add(s); }
    }

    // ── HTML Builder ──────────────────────────────────────────────────────────

    private String buildHtml(
            AdmitCardRoutineResponseDto routine,
            Map<String, StudentAdmitData> studentDataMap,
            Map<String, EnrollmentResponseDto> enrollmentMap
    ) {
        List<StudentAdmitData> students = new ArrayList<>(studentDataMap.values());
        StringBuilder pages = new StringBuilder();

        for (int i = 0; i < students.size(); i += 2) {
            StudentAdmitData s1 = students.get(i);
            StudentAdmitData s2 = (i + 1 < students.size()) ? students.get(i + 1) : null;

            EnrollmentResponseDto e1 = enrollmentMap.get(s1.studentSystemId);
            EnrollmentResponseDto e2 = s2 != null
                    ? enrollmentMap.get(s2.studentSystemId) : null;

            pages.append("<div class=\"page\">");
            pages.append(buildCard(routine, s1, e1));
            pages.append("<div class=\"card-divider\"></div>");
            if (s2 != null) pages.append(buildCard(routine, s2, e2));
            pages.append("</div>");
        }

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + getCss()
                + "</style></head><body>"
                + pages
                + "</body></html>";
    }

    private String buildCard(
            AdmitCardRoutineResponseDto routine,
            StudentAdmitData data,
            EnrollmentResponseDto enrollment
    ) {
        String name      = enrollment != null
                ? nvl(enrollment.getNameEnglish(), "N/A") : "N/A";
        String phone     = enrollment != null
                ? nvl(enrollment.getMotherPhone(), "N/A") : "N/A";
        String groupName = (enrollment != null && enrollment.getStudentGroup() != null)
                ? enrollment.getStudentGroup().getName() : "N/A";

        String photoUrl = (enrollment != null
                && enrollment.getImage() != null
                && Boolean.TRUE.equals(enrollment.getImage().getIsActive())
                && enrollment.getImage().getImageUrl() != null)
                ? idCardService.fetchAndCompressToBase64(
                enrollment.getImage().getImageUrl())
                : "";

        String photoTag = photoUrl.isEmpty()
                ? "<div class=\"photo-placeholder\">PHOTO</div>"
                : "<img src=\"" + photoUrl
                + "\" style=\"width:100%;height:100%;object-fit:cover;display:block;\">";

        String logoTag = logoBase64.isEmpty()
                ? "<div style=\"font-size:11px;color:#888;\">LOGO</div>"
                : "<img src=\"" + logoBase64
                + "\" style=\"width:68px;height:68px;object-fit:contain;\">";

        // Section label
        String sectionLabel = nvl(data.genderSectionName, "");
        if (data.sectionName != null && !data.sectionName.isBlank())
            sectionLabel += (sectionLabel.isEmpty() ? "" : " - ") + data.sectionName;
        if (sectionLabel.isEmpty()) sectionLabel = "N/A";

        // Full schedule sorted by date
        List<SessionDto> sessions = new ArrayList<>(data.sessions);
        sessions.sort(Comparator.comparing(SessionDto::getDate));
        int half         = (int) Math.ceil(sessions.size() / 2.0);
        List<SessionDto> left  = sessions.subList(0, half);
        List<SessionDto> right = sessions.subList(half, sessions.size());

        // Exam title + academic year
        String examLabel = nvl(routine.getTitle(), "EXAMINATION");
        if (routine.getAcademicYearName() != null
                && !routine.getAcademicYearName().isBlank())
            examLabel += " — " + routine.getAcademicYearName();

        // Session info line (class | section)
        String sessionLabel = nvl(data.className, "");
        if (!sectionLabel.equals("N/A"))
            sessionLabel += (sessionLabel.isEmpty() ? "" : " | ") + sectionLabel;

        return "<div class=\"admit-card\">"

                // ── Header: logo | school info | photo ──
                + "<div class=\"ac-header\">"
                + "<div class=\"ac-logo\">" + logoTag + "</div>"
                + "<div class=\"ac-header-center\">"
                + "<div class=\"ac-arabic\">بسم الله الرحمن الرحيم</div>"
                + "<div class=\"ac-school\">LUTFUR RAHMAN ALIM MADRASAH</div>"
                + "<div class=\"ac-address\">LUTFUR RAHMAN ROAD, NATULLABAD, BARISHAL</div>"
                + "</div>"
                + "<div class=\"ac-photo\">" + photoTag + "</div>"
                + "</div>"

                + "<hr class=\"ac-divider-line\">"

                // ── Exam name → session info → ADMIT CARD badge ──
                + "<div class=\"ac-exam-section\">"
                + "<div class=\"ac-exam-box\">" + examLabel + "</div>"
                + "<div class=\"ac-session-info\"+ \"<div style=\\\"height:8px;\\\"></div>\"\n>" + sessionLabel + "</div>"
                + "<div class=\"ac-admit-badge\">ADMIT CARD</div>"
                + "</div>"

                + "<hr class=\"ac-divider-line\">"

                // ── Student info ──
                + "<div class=\"ac-info\">"
                + "<table class=\"info-table\">"
                + infoRow("Student ID",     data.studentSystemId)
                + infoRow("Student's Name", "<b>" + name + "</b>")
                + infoRow("Class",          nvl(data.className, "N/A"))
                + infoRow("Roll",           data.classRoll != null
                ? data.classRoll.toString() : "N/A")
                + "</table>"
                + "<table class=\"info-table\">"
                + infoRow("Group",         groupName)
                + infoRow("Section",       sectionLabel)
                + infoRow("Parent Mobile", phone)
                + "</table>"
                + "</div>"

                // ── Schedule tables with room column ──
                + "<div class=\"ac-tables\">"
                + buildScheduleTable(left,  data.roomName)
                + buildScheduleTable(right, data.roomName)
                + "</div>"

                // ── Instruction ──
                + "<div class=\"ac-instruction\"><b>Instruction:</b>"
                + "<ol style=\"margin:4px 0 0 16px;padding:0;\">"
                + "<li>Examinees must enter the exam hall at least 15 minutes before the exam starts.</li>"
                + "</ol></div>"

                // ── Footer ──
                + "<div class=\"ac-footer\">"
                + "<div class=\"sig\">_________________<br>Class Teacher</div>"
                + (signatureBase64.isEmpty()
                ? "<div class=\"sig\">_________________<br>Principal</div>"
                : "<div class=\"sig\"><img src=\"" + signatureBase64
                + "\" style=\"height:28px;object-fit:contain;\"><br>"
                + "<div style=\"border-top:1px solid #333;padding-top:2px;\">"
                + "Principal</div></div>")
                + "</div>"

                + "</div>";
    }

    private String buildScheduleTable(List<SessionDto> sessions, String roomName) {
        if (sessions.isEmpty()) return "<table class=\"schedule\"></table>";
        StringBuilder rows = new StringBuilder();
        for (SessionDto s : sessions) {
            rows.append("<tr>")
                    .append("<td>").append(formatDate(s.getDate())).append("</td>")
                    .append("<td>").append(nvl(s.getSubjectName(), "")).append("</td>")
                    .append("<td>").append(formatTime(s.getStartTime()))
                    .append(" - ").append(formatTime(s.getEndTime())).append("</td>")
                    .append("<td>").append(nvl(roomName, "N/A")).append("</td>")
                    .append("</tr>");
        }
        return "<table class=\"schedule\">"
                + "<tr><th>Date</th><th>Subject</th><th>Time</th><th>Room</th></tr>"
                + rows
                + "</table>";
    }

    private String infoRow(String label, String value) {
        return "<tr><td class=\"lbl\">" + label + "</td>"
                + "<td class=\"sep\">:</td>"
                + "<td class=\"val\">" + value + "</td></tr>";
    }

    private String nvl(String s, String fallback) {
        return (s != null && !s.isBlank()) ? s : fallback;
    }

    private String formatDate(String date) {
        if (date == null) return "";
        try {
            String[] p = date.split("-");
            return p[2] + "-" + p[1] + "-" + p[0];
        } catch (Exception e) { return date; }
    }

    private String formatTime(String time) {
        if (time == null) return "";
        try {
            String[] p    = time.split(":");
            int hour      = Integer.parseInt(p[0]);
            String min    = p[1];
            String period = hour >= 12 ? "PM" : "AM";
            int h12       = hour > 12 ? hour - 12 : hour == 0 ? 12 : hour;
            return h12 + ":" + min + " " + period;
        } catch (Exception e) { return time; }
    }

    private String getCss() {
        return "* { box-sizing: border-box; margin: 0; padding: 0; }"
                + "body { background: #fff; font-family: Arial, sans-serif; }"
                + ".page { width: 794px; height: 1123px; display: flex;"
                + "  flex-direction: column; justify-content: space-evenly; padding: 20px; }"
                + ".card-divider { border-top: 1px dashed #aaa; margin: 0 20px; }"

                + ".admit-card { width: 100%; border: 7px solid #6ec1e4; padding: 16px;"
                + "  position: relative; background: #fff; }"
                + ".admit-card::before { content:''; position:absolute; top:7px; left:7px;"
                + "  right:7px; bottom:7px; border:3px solid #f4c542; pointer-events:none; }"

                + ".ac-header { display:flex; align-items:center; gap:12px;"
                + "  padding-bottom:12px; }"
                + ".ac-logo { width:68px; height:68px; flex-shrink:0; display:flex;"
                + "  align-items:center; justify-content:center; }"
                + ".ac-header-center { flex:1; text-align:center; }"
                + ".ac-arabic { font-size:14px; margin-bottom:3px; }"
                + ".ac-school { font-size:17px; font-weight:bold; margin-bottom:2px; }"
                + ".ac-address { font-size:11px; color:#333; }"
                + ".ac-photo { width:72px; height:88px; border:1px solid #ccc; flex-shrink:0;"
                + "  overflow:hidden; display:flex; align-items:center;"
                + "  justify-content:center; }"
                + ".photo-placeholder { font-size:11px; color:#888; text-align:center; }"

                + ".ac-divider-line { border:none; border-top:1px solid #ccc; margin:0; }"

                + ".ac-exam-section { text-align:center; margin:14px 0; }"
                + ".ac-exam-box { display:inline-block; border:2px solid #6ec1e4;"
                + "  padding:5px 18px; font-weight:bold; font-size:13px; margin-bottom:6px; }"
                + ".ac-session-info { font-size:12px; color:#444; margin-bottom:8px;"
                + "  font-weight:600; }"
                + ".ac-admit-badge { display:inline-block; background:#6ec1e4; color:#fff;"
                + "  font-weight:bold; font-size:12px; padding:4px 28px; }"

                + ".ac-info { display:flex; gap:16px; margin:12px 0; }"
                + ".info-table { border-collapse:collapse; font-size:12px; flex:1; }"
                + ".info-table td { padding:3px 5px; }"
                + ".info-table td.lbl { font-weight:bold; white-space:nowrap; }"
                + ".info-table td.sep { width:8px; }"

                + ".ac-tables { display:flex; gap:8px; margin-top:10px; }"
                + ".schedule { width:50%; border-collapse:collapse; font-size:11px; }"
                + ".schedule th, .schedule td { border:1px solid #333; padding:4px 6px;"
                + "  text-align:center; }"
                + ".schedule th { background:#eee; }"

                + ".ac-instruction { margin-top:10px; font-size:11px; }"
                + ".ac-footer { margin-top:16px; display:flex; justify-content:space-between;"
                + "  font-size:12px; text-align:center; padding:0 20px; }"
                + ".sig { text-align:center; }";
    }
}