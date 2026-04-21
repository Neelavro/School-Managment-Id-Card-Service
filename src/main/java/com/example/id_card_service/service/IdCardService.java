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
public class IdCardService {

    private static final int COLS           = 6;
    private static final int ROWS           = 3;
    private static final int CARDS_PER_PAGE = COLS * ROWS;
    private static final int CARD_W         = 190;
    private static final int CARD_H         = 300;
    private static final int GAP            = 6;
    private static final int PAGE_PAD       = 10;
    private static final int PAGE_W         = COLS * CARD_W + (COLS - 1) * GAP + PAGE_PAD * 2;
    private static final int PAGE_H         = ROWS * CARD_H + (ROWS - 1) * GAP + PAGE_PAD * 2;

    private String signatureBase64;
    private String logoBase64;

    @PostConstruct
    public void init() {
        try {
            InputStream is = getClass().getResourceAsStream("/static/signature.png");
            signatureBase64 = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) {
            signatureBase64 = "";
            System.err.println("Warning: Could not load signature image. " + e.getMessage());
        }

        try {
            InputStream is = getClass().getResourceAsStream("/static/logo-2.png");
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

    public byte[] generatePdf(List<EnrollmentResponseDto> enrollments) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            Page page = browser.newPage();

            page.setContent(buildHtml(enrollments), new Page.SetContentOptions()
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

    public byte[] generateBackPdf() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true)
            );
            Page page = browser.newPage();

            page.setContent(buildBackHtml(), new Page.SetContentOptions()
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

    private String buildHtml(List<EnrollmentResponseDto> enrollments) {
        StringBuilder cards = new StringBuilder();

        for (int i = 0; i < enrollments.size(); i++) {
            EnrollmentResponseDto s = enrollments.get(i);

            if (i > 0 && i % CARDS_PER_PAGE == 0) {
                cards.append("<div class=\"page-break\"></div>");
            }

            String photoUrl = (s.getImage() != null && Boolean.TRUE.equals(s.getImage().getIsActive()))
                    ? fetchAndCompressToBase64(s.getImage().getImageUrl())
                    : "";

            String photoTag = photoUrl.isEmpty()
                    ? "<div class=\"photo-placeholder\">Photo</div>"
                    : "<img src=\"" + photoUrl + "\" style=\"width:100%;height:100%;object-fit:cover;\">";

            String sigTag = signatureBase64.isEmpty()
                    ? ""
                    : "<img src=\"" + signatureBase64 + "\" class=\"signature-img\">";

            String name = s.getNameEnglish();
            String nameFontSize = name.length() > 28 ? "7.5px" : name.length() > 22 ? "8.5px" : name.length() > 16 ? "10px" : "12px";

            // Build info rows
            StringBuilder infoRows = new StringBuilder();
            infoRows.append("<tr>")
                    .append("<td class=\"lbl\">Class</td>")
                    .append("<td class=\"sep\">:</td>")
                    .append("<td class=\"val\">").append(s.getStudentClass() != null ? s.getStudentClass().getName() : "N/A").append("</td>")
                    .append("</tr>");
            infoRows.append("<tr>")
                    .append("<td class=\"lbl\">Roll</td>")
                    .append("<td class=\"sep\">:</td>")
                    .append("<td class=\"val\">").append(s.getClassRoll() != null ? s.getClassRoll() : "N/A").append("</td>")
                    .append("</tr>");
            infoRows.append("<tr>")
                    .append("<td class=\"lbl\">Shift</td>")
                    .append("<td class=\"sep\">:</td>")
                    .append("<td class=\"val\">").append(s.getShift() != null ? s.getShift().getName() : "N/A").append("</td>")
                    .append("</tr>");
            if (s.getGenderSection() != null) {
                String sectionLabel = s.getGenderSection().getGenderName();
                if (s.getSection() != null && s.getSection().getSectionName() != null) {
                    sectionLabel += " - " + s.getSection().getSectionName();
                }
                infoRows.append("<tr>")
                        .append("<td class=\"lbl\">Section</td>")
                        .append("<td class=\"sep\">:</td>")
                        .append("<td class=\"val\">").append(sectionLabel).append("</td>")
                        .append("</tr>");
            }
            if (s.getStudentGroup() != null) {
                infoRows.append("<tr>")
                        .append("<td class=\"lbl\">Group</td>")
                        .append("<td class=\"sep\">:</td>")
                        .append("<td class=\"val\">").append(s.getStudentGroup().getName()).append("</td>")
                        .append("</tr>");
            }

            infoRows.append("<tr>")
                    .append("<td class=\"lbl\">Year</td>")
                    .append("<td class=\"sep\">:</td>")
                    .append("<td class=\"val\">").append(s.getAcademicYear() != null ? s.getAcademicYear().getYearName() : "N/A").append("</td>")
                    .append("</tr>");
            infoRows.append("<tr>")
                    .append("<td class=\"lbl\">Mobile</td>")
                    .append("<td class=\"sep\">:</td>")
                    .append("<td class=\"val\">").append(s.getMotherPhone() != null ? s.getMotherPhone() : "N/A").append("</td>")
                    .append("</tr>");

            cards.append("<div class=\"card\">")

                    // Moon arc SVG — absolute to .card, always pinned to card bottom
                    .append("<svg class=\"arc-svg\" viewBox=\"0 0 190 95\" preserveAspectRatio=\"none\" height=\"95\" xmlns=\"http://www.w3.org/2000/svg\">")
                    .append("<path d=\"M0,95 L190,95 L190,24 Q95,88 0,76 Z\" fill=\"#cdeeb7\"/>")
                    .append("</svg>")

                    // Top teal strip
                    .append("<div class=\"card-header\"></div>")

                    // Body — flex column
                    .append("<div class=\"card-body\">")

                    // School info
                    .append("<div class=\"school-info\">")
                    .append("<div class=\"school-name\">LUTFUR RAHMAN ALIM MADRASAH</div>")
                    .append("<div class=\"school-address\">LUTFUR RAHMAN ROAD, NATULLABAD, BARISHAL</div>")
                    .append("</div>")

                    // Photo row with yellow badges
                    .append("<div class=\"photo-row\">")
                    .append("<div class=\"side-badge left\">ID CARD</div>")
                    .append("<div class=\"photo-frame\">").append(photoTag).append("</div>")
                    .append("<div class=\"side-badge right\">").append(s.getStudentSystemId()).append("</div>")
                    .append("</div>")

                    // Student name
                    .append("<div class=\"student-name\" style=\"font-size:" + nameFontSize + ";\">").append(name).append("</div>")

                    // Info table
                    .append("<table class=\"info-table\">").append(infoRows).append("</table>")

                    // Signature
                    .append("<div class=\"sig-area\">")
                    .append(sigTag)
                    .append("<div class=\"sig-line\"></div>")
                    .append("<div class=\"principal-label\">PRINCIPAL</div>")
                    .append("</div>")

                    .append("</div>") // end card-body

                    // Bottom teal strip
                    .append("<div class=\"card-footer\"></div>")

                    .append("</div>"); // end card
        }

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + getCss()
                + "</style></head><body>"
                + "<div class=\"grid\">" + cards + "</div>"
                + "</body></html>";
    }

    private String buildBackHtml() {
        String logoTag = logoBase64.isEmpty()
                ? "<div class=\"logo-placeholder\">LRMA</div>"
                : "<img src=\"" + logoBase64 + "\" class=\"logo-img\" alt=\"Logo\">";

        String card = "<div class=\"card-back\">"
                + "<div class=\"back-top-text\">"
                + "This card is not transferable.<br>"
                + "Always carry your card with you.<br>"
                + "In case of loss, inform issuing authority as if found, please return to below address."
                + "</div>"
                + "<div class=\"back-logo-center\">" + logoTag + "</div>"
                + "<div class=\"back-school-name\">LUTFUR RAHMAN ALIM MADRASAH</div>"
                + "<div class=\"back-info\">"
                + "<div><span class=\"back-label\">EIIN:</span> 137732</div>"
                + "<div>LUTFUR RAHMAN ROAD</div>"
                + "<div>NATULLABAD, BARISHAL</div>"
                + "<div><span class=\"back-label\">Mobile:</span> 01712-951422</div>"
                + "<div><span class=\"back-label\">Email:</span><br>lutfurrahmanmodelmadrasah2003@gmail.com</div>"
                + "</div>"
                + "<div class=\"back-footer\">Validity Date : 31-12-2026</div>"
                + "</div>";

        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><style>"
                + getCss()
                + getBackCss()
                + "</style></head><body>"
                + "<div class=\"grid\">" + card + "</div>"
                + "</body></html>";
    }

    private String getCss() {
        return "* { box-sizing: border-box; margin: 0; padding: 0; }"
                + "body { background: #f0f0f0; font-family: 'Times New Roman', Times, serif; padding: " + PAGE_PAD + "px; }"
                + ".grid { display: flex; flex-wrap: wrap; gap: " + GAP + "px; }"

                // Card shell — flex column so header/footer are fixed, body flexes
                + ".card {"
                + "  width: " + CARD_W + "px;"
                + "  height: " + CARD_H + "px;"
                + "  background: #ffffff;"
                + "  border: 2px dashed #999;"
                + "  position: relative;"
                + "  overflow: hidden;"
                + "  display: flex;"
                + "  flex-direction: column;"
                + "}"

                // Top teal strip
                + ".card-header { background: #2e7d6e; height: 12px; flex-shrink: 0; }"

                // Body — flex column
                + ".card-body {"
                + "  flex: 1;"
                + "  background: linear-gradient(to bottom, #b8ddf5 0%, #d4eefb 40%, #eaf6fd 65%, #ffffff 100%);"
                + "  padding: 5px 7px 0;"
                + "  position: relative;"
                + "  display: flex;"
                + "  flex-direction: column;"
                + "  overflow: hidden;"
                + "}"

                // Moon arc — absolute, sits at bottom of body
                + ".arc-svg { position: absolute; bottom: 12px; left: 0; width: 100%; pointer-events: none; z-index: 1; }"

                // School info
                + ".school-info { text-align: center; margin-bottom: 6px; position: relative; z-index: 1; }"
                + ".school-name { font-size: 14px; font-weight: 900; color: #1a3a8a; line-height: 1.2; }"
                + ".school-address { font-size: 8px; color: #cc1a1a; font-weight: 700; margin-top: 1px; text-transform: uppercase; white-space: nowrap; letter-spacing: -0.5px; word-spacing: -1.5px; }"

                // Photo row
                + ".photo-row {"
                + "  display: flex;"
                + "  align-items: center;"
                + "  justify-content: space-between;"
                + "  margin-bottom: 6px;"
                + "  position: relative;"
                + "  z-index: 1;"
                + "}"

                // Yellow side badges
                + ".side-badge {"
                + "  background: #f5c518;"
                + "  color: #1a1a1a;"
                + "  font-size: 11px;"
                + "  font-weight: 700;"
                + "  letter-spacing: 1px;"
                + "  text-transform: uppercase;"
                + "  writing-mode: vertical-rl;"
                + "  text-orientation: mixed;"
                + "  padding: 5px 6px;"
                + "  border-radius: 3px;"
                + "  line-height: 1;"
                + "  flex-shrink: 0;"
                + "  align-self: center;"
                + "}"
                + ".side-badge.left { transform: rotate(180deg); }"

                // Photo frame
                + ".photo-frame {"
                + "  width: 62px;"
                + "  height: 72px;"
                + "  border: 2px solid #2e7d6e;"
                + "  border-radius: 3px;"
                + "  overflow: hidden;"
                + "  background: #b8d8f0;"
                + "  display: flex;"
                + "  align-items: center;"
                + "  justify-content: center;"
                + "}"
                + ".photo-placeholder { font-size: 10px; color: #4a7a9a; text-align: center; line-height: 1.4; }"

                // Student name — fixed height so info table never shifts regardless of name length
                + ".student-name {"
                + "  text-align: center;"
                + "  color: #5b1fa8;"
                + "  font-weight: 900;"
                + "  font-size: 12px;"
                + "  margin: 0 0 5px;"
                + "  position: relative;"
                + "  z-index: 1;"
                + "  height: 14px;"
                + "  line-height: 14px;"
                + "}"

                // Info table
                + ".info-table { width: 100%; border-collapse: collapse; position: relative; z-index: 1; }"
                + ".info-table td { padding: 1px 2px; font-size: 8.95px; line-height: 1.3; color: #111; }"
                + ".info-table td.lbl { font-weight: 700; width: 38px; color: #111; }"
                + ".info-table td.sep { width: 8px; color: #444; }"
                + ".info-table td.val { font-weight: 600; color: #111; }"
                + ".info-table tr:not(:first-child) td { border-top: 1px dashed #cde8d0; }"

                // Signature
                + ".sig-area {"
                + "  display: flex;"
                + "  flex-direction: column;"
                + "  align-items: flex-end;"
                + "  position: absolute;"
                + "  bottom: 1px;"
                + "  right: 3px;"
                + "  z-index: 2;"
                + "}"
                + ".signature-img { display: block; margin-left: auto; width: 60px; height: 24px; object-fit: contain; }"
                + ".sig-line { border-top: 1px dashed #555; width: 70px; margin-bottom: 1px; }"
                + ".principal-label { font-size: 9.5px; font-weight: 700; color: #222; letter-spacing: 1px; }"

                // Bottom teal strip
                + ".card-footer { background: #2e7d6e; height: 12px; flex-shrink: 0; }"

                + ".page-break { width: 100%; page-break-before: always; break-before: page; }";
    }

    private String getBackCss() {
        return ".card-back {"
                + "  width: " + CARD_W + "px;"
                + "  height: " + CARD_H + "px;"
                + "  background: #ffffff;"
                + "  border: 2px dashed #999;"
                + "  padding: 14px 16px;"
                + "  position: relative;"
                + "  overflow: hidden;"
                + "  display: flex;"
                + "  flex-direction: column;"
                + "  align-items: center;"
                + "  text-align: center;"
                + "}"
                + ".back-top-text { font-size: 7px; font-weight: bold; line-height: 1.5; color: #111; margin-bottom: 10px; }"
                + ".back-logo-center { margin: 6px 0; }"
                + ".logo-img { width: 50px; height: 50px; object-fit: contain; }"
                + ".logo-placeholder { font-size: 10px; font-weight: bold; color: #1d3e8a; }"
                + ".back-school-name { font-size: 9px; font-weight: bold; color: #111; line-height: 1.4; margin: 6px 0; }"
                + ".back-info { font-size: 7.5px; line-height: 1.6; color: #222; margin-top: 4px; }"
                + ".back-label { font-weight: bold; }"
                + ".back-footer { margin-top: 10px; font-size: 8px; font-weight: bold; color: #111; }";
    }
}