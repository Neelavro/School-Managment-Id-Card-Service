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

    // A4 portrait at 96 DPI = 794 × 1123 px
    // Page padding and gap between cards
    private static final int PAGE_PAD       = 10;
    private static final int GAP            = 8;

    // Card dimensions — smaller than the full A4 fill so gaps between cards are visible.
    // Horizontal gap per side: (794 - 2*PAGE_PAD - 2*CARD_W - GAP) / 2 ≈ 24px each side
    // Vertical gap between rows: (1123 - 2*PAGE_PAD - 3*CARD_H) / 2 ≈ 36px each gap
    private static final int CARD_W         = 360;
    private static final int CARD_H         = 340;

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
                    .setFormat("A4")
                    .setLandscape(false)
                    .setMargin(new Margin()
                            .setTop(PAGE_PAD + "px")
                            .setBottom(PAGE_PAD + "px")
                            .setLeft(PAGE_PAD + "px")
                            .setRight(PAGE_PAD + "px"))
            );

            browser.close();
            return pdf;
        }
    }

    /**
     * Estimates rendered pixel width of a string in Arial Bold at 13px.
     * Uses per-character width weights to handle wide uppercase names correctly.
     */
    private double estimateNameWidth(String name) {
        double total = 0;
        for (char c : name.toCharArray()) {
            total += getCharWeight(c);
        }
        return total;
    }

    private double getCharWeight(char c) {
        return switch (c) {
            case 'M', 'W'                                        -> 12.0;
            case 'D', 'G', 'H', 'K', 'N', 'O', 'Q', 'U',
                 'X', 'Y', 'Z'                                   -> 10.5;
            case 'A', 'B', 'C', 'E', 'F', 'J', 'L', 'P',
                 'R', 'S', 'T', 'V'                              -> 9.5;
            case 'm', 'w'                                        -> 11.0;
            case 'd', 'g', 'h', 'k', 'n', 'o', 'p', 'q',
                 'u', 'x', 'y', 'z'                              -> 8.5;
            case 'a', 'b', 'c', 'e', 'f', 'r', 's', 't', 'v'   -> 8.0;
            case 'I', '1'                                        -> 4.5;
            case 'i', 'j', 'l'                                   -> 4.0;
            case '.', ',', '!', '|', ':', ';'                    -> 4.0;
            case ' '                                             -> 4.5;
            case '-', '_'                                        -> 6.0;
            default                                              -> 8.5;
        };
    }

    private String buildHtml(List<EnrollmentResponseDto> enrollments, String examName) {
        StringBuilder pages = new StringBuilder();
        StringBuilder currentPage = new StringBuilder();

        for (int i = 0; i < enrollments.size(); i++) {
            EnrollmentResponseDto s = enrollments.get(i);

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
                    ? "<div style=\"font-size:11px;font-weight:bold;\">LRMA</div>"
                    : "<img src=\"" + logoBase64 + "\" style=\"width:70px;height:70px;object-fit:contain;\">";

            // Section label
            String sectionLabel;
            if (s.getGenderSection() != null) {
                sectionLabel = s.getGenderSection().getGenderName();
                if (s.getSection() != null && s.getSection().getSectionName() != null) {
                    sectionLabel += " - " + s.getSection().getSectionName();
                }
            } else {
                sectionLabel = "N/A";
            }

            String className = s.getStudentClass() != null ? s.getStudentClass().getName() : "N/A";
            String shiftName = s.getShift() != null ? s.getShift().getName() : "N/A";
            String groupName = s.getStudentGroup() != null ? s.getStudentGroup().getName() : null;
            String yearName  = s.getAcademicYear() != null ? s.getAcademicYear().getYearName() : "N/A";
            String roll      = s.getClassRoll() != null ? String.valueOf(s.getClassRoll()) : "N/A";

            // Full exam label shown inside the exam bar: e.g. "HALF YEARLY EXAM 2025"
            String resolvedExamName = (examName != null && !examName.isBlank())
                    ? examName.toUpperCase() + " " + yearName
                    : "EXAM " + yearName;

            // ── Name display tier ──────────────────────────────────────────────
            // Available width for name cell at larger card size ≈ 270px
            String nameEnglish = s.getNameEnglish() != null ? s.getNameEnglish() : "";
            double nameWidth   = estimateNameWidth(nameEnglish);

            String nameClass;
            if      (nameWidth <= 240) nameClass = "name-normal";
            else if (nameWidth <= 270) nameClass = "name-tight";
            else if (nameWidth <= 300) nameClass = "name-tighter";
            else                       nameClass = "name-squeeze";

            // rowspan covers all rows EXCEPT the name row:
            // studentId, class/shift, [group,] section
            int rollRowSpan = groupName != null ? 3 : 2;

            currentPage.append("<div class=\"card\">")

                    // Header
                    .append("<div class=\"card-header\">")
                    .append("<div class=\"school-name\">LUTFUR RAHMAN ALIM MADRASAH</div>")
                    .append("<div class=\"school-address\">LUTFUR RAHMAN ROAD, NATULLABAD, BARISHAL</div>")
                    .append("</div>")

                    // Top row: photo | exam bar (title + full exam name beneath) | logo
                    .append("<div class=\"top-row\">")
                    .append("<div class=\"photo-frame\">").append(photoTag).append("</div>")
                    .append("<div class=\"exam-bar\">")
                    .append("<div class=\"exam-bar-title\">Exam Seat Plan</div>")
                    .append("<div class=\"exam-bar-name\">").append(resolvedExamName).append("</div>")
                    .append("</div>")
                    .append("<div class=\"logo-box\">").append(logoTag).append("</div>")
                    .append("</div>")

                    // Info table — name row is full-width, roll box rowspans the rest
                    .append("<div class=\"info-table-wrap\"><table class=\"info-table\">")

                    // Row 1: Name — spans all 4 columns (lbl + sep + val + roll), no roll cell here
                    .append("<tr class=\"name-row\">")
                    .append("<td class=\"lbl\">Name</td>")
                    .append("<td class=\"sep\">:</td>")
                    .append("<td class=\"val name-val\" colspan=\"2\"><b class=\"").append(nameClass).append("\">").append(nameEnglish).append("</b></td>")
                    .append("</tr>")

                    // Row 2: Student ID — roll box starts here with rowspan
                    .append("<tr>")
                    .append("<td class=\"lbl\">Student ID</td><td class=\"sep\">:</td><td class=\"val\">").append(s.getStudentSystemId()).append("</td>")
                    .append("<td rowspan=\"").append(rollRowSpan).append("\" class=\"roll-cell\">")
                    .append("<div class=\"roll-box\">")
                    .append("<div class=\"roll-title\">Roll No.</div>")
                    .append("<div class=\"roll-number\">").append(roll).append("</div>")
                    .append("</div>")
                    .append("</td>")
                    .append("</tr>")

                    .append("<tr><td class=\"lbl\">Class / Shift</td><td class=\"sep\">:</td><td class=\"val\">").append(className).append(" / ").append(shiftName).append("</td></tr>");

            if (groupName != null) {
                currentPage.append("<tr><td class=\"lbl\">Group</td><td class=\"sep\">:</td><td class=\"val\">").append(groupName).append("</td></tr>");
            }

            currentPage
                    .append("<tr><td class=\"lbl\">Section</td><td class=\"sep\">:</td><td class=\"val\">").append(sectionLabel).append("</td></tr>")
                    .append("</table></div>")
                    .append("</div>"); // end card
        }

        pages.append("<div class=\"page\">").append(currentPage).append("</div>");

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + getCss()
                + "</style></head><body>"
                + pages
                + "</body></html>";
    }

    private String getCss() {
        return "* { box-sizing: border-box; margin: 0; padding: 0; }"
                + "body { background: #fff; font-family: Arial, sans-serif; }"

                // Page fills exactly one A4 sheet — Playwright's margin handles the outer padding,
                // so the .page div itself is sized to the available inner area.
                + ".page {"
                + "  width: " + (794 - 2 * PAGE_PAD) + "px;"
                + "  height: " + (1123 - 2 * PAGE_PAD) + "px;"
                + "  display: flex;"
                + "  flex-wrap: wrap;"
                + "  justify-content: space-evenly;"
                + "  align-content: space-evenly;"
                + "  page-break-after: always;"
                + "  break-after: page;"
                + "  overflow: hidden;"
                + "}"

                + ".card {"
                + "  width: " + CARD_W + "px;"
                + "  height: " + CARD_H + "px;"
                + "  border: 2px solid #000;"
                + "  padding: 6px 8px;"
                + "  display: flex;"
                + "  flex-direction: column;"
                + "  overflow: hidden;"
                + "}"

                + ".card-header { text-align: center; margin-bottom: 4px; flex-shrink: 0; }"
                + ".school-name { font-weight: bold; font-size: 14px; line-height: 1.2; }"
                + ".school-address { font-size: 10px; margin-top: 2px; margin-bottom: 6px; }"

                + ".top-row {"
                + "  display: flex;"
                + "  align-items: center;"
                + "  gap: 8px;"
                + "  margin-bottom: 4px;"
                + "  flex-shrink: 0;"
                + "}"

                + ".photo-frame {"
                + "  width: 72px;"
                + "  height: 80px;"
                + "  border: 1px solid #000;"
                + "  flex-shrink: 0;"
                + "  overflow: hidden;"
                + "  display: flex;"
                + "  align-items: center;"
                + "  justify-content: center;"
                + "  background: #ddd;"
                + "}"
                + ".photo-placeholder { font-size: 11px; color: #555; text-align: center; }"

                // exam-bar is a flex column: title on top, exam name below
                + ".exam-bar {"
                + "  flex: 1;"
                + "  background: #cfcfcf;"
                + "  text-align: center;"
                + "  padding: 4px 6px;"
                + "  display: flex;"
                + "  flex-direction: column;"
                + "  align-items: center;"
                + "  justify-content: center;"
                + "  gap: 3px;"
                + "}"
                + ".exam-bar-title {"
                + "  font-weight: bold;"
                + "  font-size: 14px;"
                + "  line-height: 1.2;"
                + "}"
                + ".exam-bar-name {"
                + "  font-size: 11px;"
                + "  font-weight: bold;"
                + "  line-height: 1.2;"
                + "}"

                + ".logo-box { width: 76px; text-align: center; flex-shrink: 0; }"

                // Info table fills remaining card height
                + ".info-table-wrap { flex: 1; overflow: hidden; }"
                + ".info-table { width: 100%; border-collapse: collapse; font-size: 13px; }"
                + ".info-table td { padding: 2px 4px; vertical-align: middle; line-height: 1.4; }"
                + ".info-table td.lbl { width: 100px; font-weight: 600; white-space: nowrap; }"
                + ".info-table td.sep { width: 10px; }"
                + ".info-table td.val { overflow: hidden; white-space: nowrap; }"

                + ".info-table td.name-val { overflow: hidden; white-space: nowrap; }"

                // ── Name tiers ────────────────────────────────────────────────
                + "b.name-normal  { font-size: 13px;  letter-spacing: normal;  word-spacing: normal;   white-space: nowrap; }"
                + "b.name-tight   { font-size: 13px;  letter-spacing: -0.4px;  word-spacing: -1px;     white-space: nowrap; }"
                + "b.name-tighter { font-size: 11px;  letter-spacing: -0.5px;  word-spacing: -1.5px;   white-space: nowrap; }"
                + "b.name-squeeze { font-size: 10px;  letter-spacing: -0.6px;  word-spacing: -2px;     white-space: nowrap; }"
                // ─────────────────────────────────────────────────────────────

                // Roll cell — rowspans Student ID through Section rows
                + ".roll-cell { width: 90px; vertical-align: middle; text-align: center; padding: 0 4px; }"
                + ".roll-box { border: 2px solid #000; text-align: center; }"
                + ".roll-title {"
                + "  border-bottom: 2px solid #000;"
                + "  font-weight: bold;"
                + "  font-size: 11px;"
                + "  padding: 2px;"
                + "}"
                + ".roll-number {"
                + "  font-size: 32px;"
                + "  font-weight: bold;"
                + "  padding: 4px 0;"
                + "  line-height: 1.1;"
                + "}";
    }
}