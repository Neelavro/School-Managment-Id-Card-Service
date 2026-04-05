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
import java.nio.file.Files;
import java.nio.file.Paths;
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
            InputStream is = getClass().getResourceAsStream("/static/signature.jpeg");
            signatureBase64 = "data:image/jpeg;base64,"
                    + Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) {
            signatureBase64 = "";
            System.err.println("Warning: Could not load signature image. " + e.getMessage());
        }

        try {
            InputStream is = getClass().getResourceAsStream("/static/logo.png");
            logoBase64 = "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(is.readAllBytes());
        } catch (Exception e) {
            logoBase64 = "";
            System.err.println("Warning: Could not load logo image. " + e.getMessage());
        }
    }

    private String fetchAndCompressToBase64(String imageUrl) {
        int MAX_BYTES = 250 * 1024;

        try {
            byte[] rawBytes = new URL(imageUrl).openStream().readAllBytes();

            BufferedImage original = ImageIO.read(new java.io.ByteArrayInputStream(rawBytes));
            if (original == null) return "";

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

            int targetW = 140;
            int targetH = (int) ((double) original.getHeight() / original.getWidth() * targetW);

            BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(original, 0, 0, targetW, targetH, null);
            g.dispose();

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

            cards.append("<div class=\"card\">")

                    .append("<div class=\"school\">")
                    .append("<h2>LUTFUR RAHMAN ALIM MADRASAH</h2>")
                    .append("<p>LUTFUR RAHMAN SARAK, NATULLABAD, BARISHAL</p>")
                    .append("</div>")

                    .append("<div class=\"id-bar\">ID CARD</div>")
                    .append("<div class=\"student-id\">").append(s.getStudentSystemId()).append("</div>")

                    .append("<div class=\"photo\">")
                    .append(photoUrl.isEmpty()
                            ? "<img src=\"https://via.placeholder.com/70x80\" style=\"width:100%;height:100%;object-fit:cover;\">"
                            : "<img src=\"" + photoUrl + "\" style=\"width:100%;height:100%;object-fit:cover;\">")
                    .append("</div>")

                    .append("<div class=\"name\">").append(s.getNameEnglish()).append("</div>")

                    .append("<table class=\"info\">")
                    .append("<tr><td class=\"label\">Class</td><td>: ")
                    .append(s.getStudentClass() != null ? s.getStudentClass().getName() : "N/A").append("</td></tr>")
                    .append("<tr><td class=\"label\">Shift</td><td>: ")
                    .append(s.getShift() != null ? s.getShift().getName() : "N/A").append("</td></tr>");

            if (s.getGenderSection() != null) {
                cards.append("<tr><td class=\"label\">Section</td><td>: ")
                        .append(s.getGenderSection().getGenderName()).append("</td></tr>");
            }
            if (s.getStudentGroup() != null) {
                cards.append("<tr><td class=\"label\">Group</td><td>: ")
                        .append(s.getStudentGroup().getName()).append("</td></tr>");
            }

            cards.append("<tr><td class=\"label\">Roll</td><td>: ")
                    .append(s.getClassRoll() != null ? s.getClassRoll() : "N/A").append("</td></tr>")
                    .append("<tr><td class=\"label\">Year</td><td>: ")
                    .append(s.getAcademicYear() != null ? s.getAcademicYear().getYearName() : "N/A").append("</td></tr>")
                    .append("<tr><td class=\"label\">Mobile</td><td>: ")
                    .append(s.getMotherPhone() != null ? s.getMotherPhone() : "N/A").append("</td></tr>")
                    .append("</table>")

                    .append("<div class=\"signature\">")
                    .append(signatureBase64.isEmpty() ? "" :
                            "<img src=\"" + signatureBase64 + "\" class=\"signature-img\">")
                    .append("<div class=\"signature-line\"></div>")
                    .append("<div class=\"principal\">PRINCIPAL</div>")
                    .append("</div>")

                    .append("<div class=\"bottom\"></div>")
                    .append("</div>");
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

                + "<div class=\"back-school-name\">"
                + "LUTFUR RAHMAN ALIM MADRASAH"
                + "</div>"

                + "<div class=\"back-info\">"
                + "<div><span class=\"back-label\">EIIN:</span> 137732</div>"
                + "<div>LUTFUR RAHMAN SARAK</div>"
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
                + "body { background: #f0f0f0; font-family: Arial, sans-serif; padding: " + PAGE_PAD + "px; }"
                + ".grid { display: flex; flex-wrap: wrap; gap: " + GAP + "px; }"
                + ".card { width: " + CARD_W + "px; height: " + CARD_H + "px; background: linear-gradient(#d9ecf7, #ffffff); border: 2px dashed #999; padding: 8px; position: relative; overflow: hidden; }"
                + ".school { text-align: center; }"
                + ".school h2 { color: #1d3e8a; font-size: 8px; line-height: 1.2; }"
                + ".school p  { font-size: 7px; margin-top: 2px; }"
                + ".id-bar { position: absolute; left: 0; top: 52px; background: #1d5bbf; color: white; padding: 6px 3px; font-weight: bold; font-size: 7px; writing-mode: vertical-rl; transform: rotate(180deg); letter-spacing: 1px; }"
                + ".student-id { position: absolute; right: 0; top: 52px; background: #1d5bbf; color: white; padding: 6px 3px; font-weight: bold; font-size: 7px; writing-mode: vertical-rl; letter-spacing: 1px; }"
                + ".photo { width: 70px; height: 80px; border: 2px solid #4da3d9; margin: 6px auto; overflow: hidden; }"
                + ".name { text-align: center; color: #7a1fa2; font-weight: bold; font-size: 8px; margin: 4px 0 2px; }"
                + ".info { width: 100%; }"
                + ".info td { padding: 1px 2px; font-size: 7.5px; line-height: 1.3; }"
                + ".label { width: 45px; font-weight: bold; }"
                + ".signature { position: absolute; bottom: 22px; width: 90%; left: 5%; text-align: right; }"
                + ".signature-img { display: block; margin-left: auto; margin-right: 0; width: 60px; height: 28px; object-fit: contain; margin-bottom: 0; }"
                + ".signature-line { border-top: 1px dashed #555; margin-bottom: 2px; }"
                + ".principal { font-size: 7px; }"
                + ".bottom { position: absolute; bottom: 0; left: 0; width: 100%; height: 20px; background: linear-gradient(90deg, #2aa4d4, #1d5bbf); }"
                + ".page-break { width: 100%; page-break-before: always; break-before: page; }";
    }

    private String getBackCss() {
        return ".card-back { width: " + CARD_W + "px; height: " + CARD_H + "px; background: #ffffff; border: 2px dashed #999; padding: 14px 16px; position: relative; overflow: hidden; display: flex; flex-direction: column; align-items: center; text-align: center; box-sizing: border-box; }"
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