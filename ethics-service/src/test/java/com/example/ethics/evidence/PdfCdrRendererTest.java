package com.example.ethics.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ethics.config.EvidenceProperties;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-104J (#2929) — CDR: the derivative is rebuilt from rasters, so active content
 * is absent by construction; the findings list exists so removal is recorded, not silent.
 *
 * <p>Fixtures are BUILT with PDFBox rather than committed as binary files: a hostile-PDF
 * corpus in the repo would itself be a quarantine problem, and a constructed fixture states
 * exactly which structure it carries.
 */
class PdfCdrRendererTest {

    private PdfCdrRenderer renderer(int maxPages, long maxPixels) {
        EvidenceProperties properties = new EvidenceProperties();
        properties.getProcessor().setMaxPdfPages(maxPages);
        properties.getProcessor().setPdfRenderDpi(96);
        properties.getProcessor().setMaxDecodedImagePixels(maxPixels);
        return new PdfCdrRenderer(properties);
    }

    private static byte[] cleanPdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Sentetik kanit sayfasi " + (i + 1));
                    content.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    /** Carries every class the acceptance names: JS OpenAction, Launch, embedded file. */
    private static byte[] hostilePdf() throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Aktif icerik tasiyan sentetik belge");
                content.endText();
            }
            // Document-level JavaScript OpenAction.
            document.getDocumentCatalog().setOpenAction(
                    new PDActionJavaScript("app.alert('sentetik');"));
            // Embedded file in the names tree.
            PDEmbeddedFile embedded = new PDEmbeddedFile(
                    document, new java.io.ByteArrayInputStream("sentetik".getBytes()));
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("sentetik.txt");
            spec.setEmbeddedFile(embedded);
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("sentetik", spec));
            PDDocumentNameDictionary names =
                    new PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            document.getDocumentCatalog().setNames(names);
            // Launch action behind a link annotation.
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(50, 600, 100, 20));
            PDActionLaunch launch = new PDActionLaunch();
            // COS-level file entry: PDFBox 3 wants a PDFileSpecification object here and
            // the /F string is all the fixture needs to be a real Launch action.
            launch.getCOSObject().setString(COSName.F, "cmd.exe");
            link.setAction(launch);
            page.getAnnotations().add(link);
            document.save(bytes);
            return bytes.toByteArray();
        }
    }

    @Test
    @DisplayName("temiz PDF: sayfalar düzleşir, bulgu listesi boş kalır")
    void aCleanPdfFlattensWithNoFindings() throws Exception {
        PdfCdrRenderer.Flattened result = renderer(100, 40_000_000L).flatten(cleanPdf(2));
        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.disarmed()).isEmpty();
        try (PDDocument rebuilt = Loader.loadPDF(result.content())) {
            assertThat(rebuilt.getNumberOfPages()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("düşman PDF: bulgular adlandırılır ve türevde HİÇBİRİ kalmaz")
    void hostileContentIsNamedAndAbsentFromTheDerivative() throws Exception {
        PdfCdrRenderer.Flattened result = renderer(100, 40_000_000L).flatten(hostilePdf());
        assertThat(result.disarmed()).contains(
                PdfCdrRenderer.OPEN_ACTION,
                PdfCdrRenderer.JAVASCRIPT,
                PdfCdrRenderer.EMBEDDED_FILE,
                PdfCdrRenderer.LAUNCH_ACTION);

        // The load-bearing claim: absence by construction, verified by re-parsing the
        // derivative and looking for every structure the fixture carried.
        try (PDDocument rebuilt = Loader.loadPDF(result.content())) {
            assertThat(rebuilt.getDocumentCatalog().getOpenAction()).isNull();
            assertThat(rebuilt.getDocumentCatalog().getNames()).isNull();
            assertThat(rebuilt.getDocumentCatalog().getAcroForm()).isNull();
            for (PDPage page : rebuilt.getPages()) {
                assertThat(page.getAnnotations()).isEmpty();
                assertThat(page.getCOSObject().getDictionaryObject(COSName.AA)).isNull();
            }
            // And a raw byte sweep for good measure — no JavaScript string survives.
            String raw = new String(result.content(), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertThat(raw).doesNotContain("app.alert").doesNotContain("cmd.exe");
        }
    }

    @Test
    @DisplayName("sayfa bombası: sınır aşımı fail-closed, işçi ayakta")
    void pageBombFailsClosedBeforeRendering() throws Exception {
        byte[] manyPages = cleanPdf(7);
        assertThatThrownBy(() -> renderer(6, 40_000_000L).flatten(manyPages))
                .isInstanceOfSatisfying(
                        EvidenceProcessor.ProcessingException.class,
                        error -> {
                            assertThat(error.code()).isEqualTo("EVIDENCE_PDF_PAGE_LIMIT");
                            assertThat(error.outcome())
                                    .isEqualTo(EvidenceProcessor.ProcessingException.Outcome.POLICY);
                        });
    }

    /** A tiny file declaring an enormous canvas — decoding it is what kills the worker. */
    @Test
    @DisplayName("piksel bombası: dev MediaBox decode edilmeden reddedilir")
    void declaredCanvasBombIsRefusedBeforeDecoding() throws Exception {
        byte[] bomb;
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(14_000f, 14_000f)));
            document.save(bytes);
            bomb = bytes.toByteArray();
        }
        assertThatThrownBy(() -> renderer(100, 40_000_000L).flatten(bomb))
                .isInstanceOfSatisfying(
                        EvidenceProcessor.ProcessingException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("EVIDENCE_IMAGE_DECOMPRESSION_LIMIT"));
    }

    @Test
    @DisplayName("PDF olmayan bayt yığını INTEGRITY ile düşer")
    void garbageFailsAsParseError() {
        byte[] garbage = "%PDF-1.7 sentetik ama gerçek değil".getBytes();
        assertThatThrownBy(() -> renderer(100, 40_000_000L).flatten(garbage))
                .isInstanceOfSatisfying(
                        EvidenceProcessor.ProcessingException.class,
                        error -> assertThat(error.code()).isEqualTo("EVIDENCE_PDF_PARSE_FAILED"));
    }

    @Test
    @DisplayName("şifreli PDF reddedilir: incelenemeyen içerik temizlenemez")
    void encryptedPdfIsRefused() throws Exception {
        byte[] encrypted;
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner-sentetik", "", new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(bytes);
            encrypted = bytes.toByteArray();
        }
        assertThatThrownBy(() -> renderer(100, 40_000_000L).flatten(encrypted))
                .isInstanceOfSatisfying(
                        EvidenceProcessor.ProcessingException.class,
                        error -> assertThat(error.code()).isEqualTo("EVIDENCE_PDF_ENCRYPTED_DENIED"));
    }

    /**
     * The lane boundary, asserted structurally: the CDR mode must never be reachable from
     * the request-facing configuration. The public API rejecting PDFs (fail-closed) while
     * the worker accepts them is the entire deployment shape — a config edit that turns the
     * API JVM into a CDR host recreates the false security claim the original comment warned
     * about.
     */
    @Test
    @DisplayName("işlemci modunun default'u disabled — CDR ancak worker config'i ile açılır")
    void theProcessorModeDefaultsToDisabled() throws Exception {
        String config = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.yml"));
        // The literal may appear in prose; what must hold is the DEFAULT: without an
        // explicit worker ConfigMap the mode is disabled, so neither the request-facing
        // JVM nor an unconfigured pod ever becomes a CDR host by accident.
        assertThat(config).contains("mode: ${ETHICS_EVIDENCE_PROCESSOR_MODE:disabled}");
    }
}
