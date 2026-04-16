package com.example.id_card_service.service;

import com.example.id_card_service.dto.EnrollmentResponseDto;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Base64;
import java.util.List;

@Service
public class SeatPlanService {

    // 2 cards per row, 3 rows = 6 cards per page
    private static final int COLS           = 2;
    private static final int ROWS           = 3;
    private static final int CARDS_PER_PAGE = COLS * ROWS;

    private static final int CARD_W         = 340;
    private static final int CARD_H         = 220;
    private static final int GAP            = 8;
    private static final int PAGE_PAD       = 10;
    private static final int PAGE_W         = COLS * CARD_W + (COLS - 1) * GAP + PAGE_PAD * 2;
    private static final int PAGE_H         = ROWS * CARD_H + (ROWS - 1) * GAP + PAGE_PAD * 2;

    private String logoBase64;

    @PostConstruct
    public void init() {
        try {
            InputStream is = getClass().getResourceAsStream("/static/logo.png");
            logoBase64 = "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) {
            logoBase64 = "";
            System.err.println("Warning: Could not load logo image. " + e.getMessage());
        }
    }

    String fetchAndCompressToBase64(String imageUrl) {
        int MAX_BYTES = 250 * 1024;

        try {
            byte[] rawBytes = new URL(imageUrl).openStream().readAllBytes();

            BufferedImage original = ImageIO.read(new java.io.ByteArrayInputStream(rawBytes));
            if (original == null) return "";

            // Fix EXIF rotation
            try {
                com.drew.metadata.Metadata metadata = com.drew.imaging.ImageMetadataReader
                        .readMetadata(new java.io.ByteArrayInputStream(rawBytes));
                com.drew.metadata.exif.ExifIFD0Directory exif = metadata
                        .getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class);

                if (exif != null && exif.containsTag(com.drew.metadata.exif.ExifIFD0Directory.TAG_ORIENTATION)) {
                    int orientation = exif.getInt(com.drew.metadata.exif.ExifIFD0Directory.TAG_ORIENTATION);
                    int degrees = switch (orientation) {
                        case 3 -> 180;
                        case 6 -> 90;
                        case 8 -> 270;
                        default -> 0;
                    };

                    if (degrees != 0) {
                        boolean swap = (degrees == 90 || degrees == 270);
                        int newW = swap ? original.getHeight() : original.getWidth();
                        int newH = swap ? original.getWidth() : original.getHeight();
                        BufferedImage rotated = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                        Graphics2D gr = rotated.createGraphics();
                        gr.translate(newW / 2.0, newH / 2.0);
                        gr.rotate(Math.toRadians(degrees));
                        gr.translate(-original.getWidth() / 2.0, -original.getHeight() / 2.0);
                        gr.drawImage(original, 0, 0, null);
                        gr.dispose();
                        original = rotated;
                    }
                }
            } catch (Exception exifEx) {
                System.err.println("Warning: Could not read EXIF, skipping rotation: " + exifEx.getMessage());
            }

            // If still landscape after EXIF fix, force rotate 90 degrees
            if (original.getWidth() > original.getHeight()) {
                int newW = original.getHeight();
                int newH = original.getWidth();
                BufferedImage rotated = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                Graphics2D gr = rotated.createGraphics();
                gr.translate(newW / 2.0, newH / 2.0);
                gr.rotate(Math.toRadians(90));
                gr.translate(-original.getWidth() / 2.0, -original.getHeight() / 2.0);
                gr.drawImage(original, 0, 0, null);
                gr.dispose();
                original = rotated;
            }

            // Scale down to 140px wide, preserve aspect ratio
            int targetW = 140;
            int targetH = (int) ((double) original.getHeight() / original.getWidth() * targetW);

            BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(original, 0, 0, targetW, targetH, null);
            g.dispose();

            // Decrease JPEG quality until under 250 KB
            float quality = 0.85f;
            byte[] result = null;

            while (quality >= 0.30f) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(resized, null, null), param);
                writer.dispose();
                ios.close();

                result = baos.toByteArray();
                if (result.length <= MAX_BYTES) break;

                quality -= 0.10f;
            }

            if (result == null || result.length == 0) return "";
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(result);

        } catch (Exception e) {
            System.err.println("Warning: Could not process student image: " + e.getMessage());
            return "";
        }
    }

    public byte[] generatePdf(List<EnrollmentResponseDto> enrollments, String examName) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            Page page = browser.newPage();

            page.setContent(buildHtml(enrollments, examName), new Page.SetContentOptions()
                    .setWaitUntil(WaitUntilState.NETWORKIDLE));

            byte[] pdf = page.pdf(new Page.PdfOptions()
                    .setPrintBackground(true)
                    .setMargin(new Margin().setTop("0").setBottom("0").setLeft("0").setRight("0"))
                    .setWidth(PAGE_W + "px")
                    .setHeight(PAGE_H + "px")
            );

            browser.close();
            return pdf;
        }
    }

    private String buildHtml(List<EnrollmentResponseDto> enrollments, String examName) {
        StringBuilder pages = new StringBuilder();
        StringBuilder currentPage = new StringBuilder();

        for (int i = 0; i < enrollments.size(); i++) {
            EnrollmentResponseDto s = enrollments.get(i);

            // Start a new page div every CARDS_PER_PAGE cards
            if (i > 0 && i % CARDS_PER_PAGE == 0) {
                pages.append("<div class=\"page\">").append(currentPage).append("</div>");
                currentPage = new StringBuilder();
            }

            // Photo
            String photoUrl = (s.getImage() != null && Boolean.TRUE.equals(s.getImage().getIsActive()))
                    ? fetchAndCompressToBase64(s.getImage().getImageUrl())
                    : "";

            String photoTag = photoUrl.isEmpty()
                    ? "<div class=\"photo-placeholder\">Photo</div>"
                    : "<img src=\"" + photoUrl + "\" style=\"width:100%;height:100%;object-fit:cover;\">";

            // Logo
            String logoTag = logoBase64.isEmpty()
                    ? "<div style=\"font-size:9px;font-weight:bold;\">LRMA</div>"
                    : "<img src=\"" + logoBase64 + "\" style=\"width:55px;height:55px;object-fit:contain;\">";

            // Section label: "GenderName - SectionName" if section exists, else just "GenderName"
            String sectionLabel;
            if (s.getGenderSection() != null) {
                sectionLabel = s.getGenderSection().getGenderName();
                if (s.getSection() != null && s.getSection().getSectionName() != null) {
                    sectionLabel += " - " + s.getSection().getSectionName();
                }
            } else {
                sectionLabel = "N/A";
            }

            // Class name
            String className = s.getStudentClass() != null ? s.getStudentClass().getName() : "N/A";

            // Shift name
            String shiftName = s.getShift() != null ? s.getShift().getName() : "N/A";

            // Group — null means don't render the row
            String groupName = s.getStudentGroup() != null ? s.getStudentGroup().getName() : null;

            // Academic year
            String yearName = s.getAcademicYear() != null ? s.getAcademicYear().getYearName() : "N/A";

            // Roll
            String roll = s.getClassRoll() != null ? String.valueOf(s.getClassRoll()) : "N/A";

            // Exam name fallback
            String resolvedExamName = (examName != null && !examName.isBlank()) ? examName.toUpperCase() : "EXAM";

            // rowspan: base 6 rows (name, studentId, year, examName, class/shift, section); +1 if group row present
            int rowSpan = groupName != null ? 7 : 6;

            currentPage.append("<div class=\"card\">")

                    // Header: school name + address
                    .append("<div class=\"card-header\">")
                    .append("<div class=\"school-name\">LUTFUR RAHMAN ALIM MADRASAH</div>")
                    .append("<div class=\"school-address\">LUTFUR RAHMAN ROAD, NATULLABAD, BARISHAL</div>")
                    .append("</div>")

                    // Top row: photo | exam bar | logo
                    .append("<div class=\"top-row\">")
                    .append("<div class=\"photo-frame\">").append(photoTag).append("</div>")
                    .append("<div class=\"exam-bar\">Exam Seat Plan</div>")
                    .append("<div class=\"logo-box\">").append(logoTag).append("</div>")
                    .append("</div>")

                    // Info table
                    .append("<div class=\"info-table-wrap\"><table class=\"info-table\">")
                    .append("<tr>")
                    .append("<td class=\"lbl\">Name</td><td class=\"sep\">:</td><td class=\"val\"><b>").append(s.getNameEnglish()).append("</b></td>")
                    .append("<td rowspan=\"").append(rowSpan).append("\" class=\"roll-cell\">")
                    .append("<div class=\"roll-box\">")
                    .append("<div class=\"roll-title\">Roll No.</div>")
                    .append("<div class=\"roll-number\">").append(roll).append("</div>")
                    .append("</div>")
                    .append("</td>")
                    .append("</tr>")

                    .append("<tr><td class=\"lbl\">Student ID</td><td class=\"sep\">:</td><td class=\"val\">").append(s.getStudentSystemId()).append("</td></tr>")
                    .append("<tr><td class=\"lbl\">Year/Session</td><td class=\"sep\">:</td><td class=\"val\">").append(yearName).append("</td></tr>")
                    .append("<tr><td class=\"lbl\">Exam Name</td><td class=\"sep\">:</td><td class=\"val\"><b>").append(resolvedExamName).append(" ").append(yearName).append("</b></td></tr>")
                    .append("<tr><td class=\"lbl\">Class / Shift</td><td class=\"sep\">:</td><td class=\"val\">").append(className).append(" / ").append(shiftName).append("</td></tr>");

            // Conditionally append group row
            if (groupName != null) {
                currentPage.append("<tr><td class=\"lbl\">Group</td><td class=\"sep\">:</td><td class=\"val\">").append(groupName).append("</td></tr>");
            }

            currentPage.append("<tr><td class=\"lbl\">Section</td><td class=\"sep\">:</td><td class=\"val\">").append(sectionLabel).append("</td></tr>")
                    .append("</table></div>")
                    .append("</div>"); // end card
        }

        // Flush the last page
        pages.append("<div class=\"page\">").append(currentPage).append("</div>");

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + getCss()
                + "</style></head><body>"
                + pages
                + "</body></html>";
    }

    private String getCss() {
        // Budget (inner height after 2px border + 4px padding top/bottom = 8px):
        //   220 - 4 (border) - 8 (padding) = 208px usable
        //   header:        ~22px  (school name 13px*1.1 + address 9px + 1px gap)
        //   header mb:       2px
        //   top-row:        56px  (photo frame height)
        //   top-row mb:      2px
        //   info-table:     ~90px (6 rows * ~13px, or 5 rows * ~13px)
        //   exam-footer:    ~16px (border-top + padding + 10px text)
        //   footer mt:       2px
        //   subtotal:      ~190px — fits with a few px to spare
        return "* { box-sizing: border-box; margin: 0; padding: 0; }"
                + "body { background: #fff; font-family: Arial, sans-serif; }"

                // Each page is exactly PAGE_W x PAGE_H, padded, breaks before printing
                + ".page {"
                + "  width: " + PAGE_W + "px;"
                + "  height: " + PAGE_H + "px;"
                + "  padding: " + PAGE_PAD + "px;"
                + "  display: flex;"
                + "  flex-wrap: wrap;"
                + "  gap: " + GAP + "px;"
                + "  page-break-after: always;"
                + "  break-after: page;"
                + "  overflow: hidden;"
                + "}"

                // Card — flex column, strictly fixed size, no overflow
                + ".card {"
                + "  width: " + CARD_W + "px;"
                + "  height: " + CARD_H + "px;"
                + "  border: 2px solid #000;"
                + "  padding: 4px 5px;"
                + "  display: flex;"
                + "  flex-direction: column;"
                + "  overflow: hidden;"
                + "}"

                // Header — fixed, does not grow
                + ".card-header { text-align: center; margin-bottom: 2px; flex-shrink: 0; }"
                + ".school-name { font-weight: bold; font-size: 12px; line-height: 1.15; }"
                + ".school-address { font-size: 8.5px; margin-top: 1px; }"

                // Top row — fixed height 56px, does not grow
                + ".top-row {"
                + "  display: flex;"
                + "  align-items: center;"
                + "  gap: 5px;"
                + "  margin-bottom: 2px;"
                + "  flex-shrink: 0;"
                + "}"

                // Photo frame — 56px tall
                + ".photo-frame {"
                + "  width: 55px;"
                + "  height: 56px;"
                + "  border: 1px solid #000;"
                + "  flex-shrink: 0;"
                + "  overflow: hidden;"
                + "  display: flex;"
                + "  align-items: center;"
                + "  justify-content: center;"
                + "  background: #ddd;"
                + "}"
                + ".photo-placeholder { font-size: 9px; color: #555; text-align: center; }"

                // Exam bar
                + ".exam-bar {"
                + "  flex: 1;"
                + "  background: #cfcfcf;"
                + "  text-align: center;"
                + "  font-weight: bold;"
                + "  font-size: 11.5px;"
                + "  padding: 0 4px;"
                + "  display: flex;"
                + "  align-items: center;"
                + "  justify-content: center;"
                + "  line-height: 1.3;"
                + "}"

                // Logo box
                + ".logo-box {"
                + "  width: 60px;"
                + "  text-align: center;"
                + "  flex-shrink: 0;"
                + "}"

                // Info table wrapper — takes remaining space, clips overflow
                + ".info-table-wrap { flex: 1; overflow: hidden; }"

                // Info table — compact rows
                + ".info-table { width: 100%; border-collapse: collapse; font-size: 10.5px; }"
                + ".info-table td { padding: 0.5px 3px; vertical-align: middle; line-height: 1.25; }"
                + ".info-table td.lbl { width: 85px; font-weight: 600; white-space: nowrap; }"
                + ".info-table td.sep { width: 8px; }"
                + ".info-table td.val { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }"

                // Roll cell + box
                + ".roll-cell { width: 88px; vertical-align: middle; text-align: center; padding: 0 3px; }"
                + ".roll-box { border: 2px solid #000; text-align: center; }"
                + ".roll-title {"
                + "  border-bottom: 2px solid #000;"
                + "  font-weight: bold;"
                + "  font-size: 9.5px;"
                + "  padding: 1px;"
                + "}"
                + ".roll-number {"
                + "  font-size: 24px;"
                + "  font-weight: bold;"
                + "  padding: 2px 0;"
                + "  line-height: 1.1;"
                + "}";
    }
}