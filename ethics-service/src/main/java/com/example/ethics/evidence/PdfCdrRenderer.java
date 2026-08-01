package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ES-104J (#2929) — content disarm &amp; reconstruction for PDF evidence.
 *
 * <p>The output is never the input with pieces removed. Every page is rendered to a raster
 * and a <em>new</em> document is built holding only those images — so JavaScript, embedded
 * files, launch actions, XFA and anything not yet on anyone's list are absent from the
 * derivative <em>by construction</em>, not by enumeration. The enumeration below exists for
 * a different reason: the acceptance requires that removal is <strong>recorded</strong>, not
 * silent. A derivative that quietly dropped a payload is indistinguishable from a clean
 * original, and the case handler deciding whether the file itself was the incident needs to
 * know the difference.
 *
 * <p>Bomb defence is two cheap ceilings checked <em>before</em> the expensive work: a page
 * count cap, and a per-page pixel cap computed from the declared MediaBox — a small file can
 * still declare an enormous canvas, and decoding it is what exhausts the worker (#2860).
 * Encrypted documents are refused outright: content that cannot be inspected cannot be
 * disarmed, and "probably fine" is not a custody claim.
 */
@Component
@ConditionalOnProperty(name = "ethics.evidence.processor.mode", havingValue = "pdf-cdr")
public class PdfCdrRenderer {

    /** Bounded finding codes — these are audit vocabulary, not free text. */
    static final String JAVASCRIPT = "JAVASCRIPT";
    static final String OPEN_ACTION = "OPEN_ACTION";
    static final String LAUNCH_ACTION = "LAUNCH_ACTION";
    static final String FORM_ACTION = "FORM_ACTION";
    static final String EMBEDDED_FILE = "EMBEDDED_FILE";
    static final String XFA_FORM = "XFA_FORM";
    static final String ADDITIONAL_ACTION = "ADDITIONAL_ACTION";
    static final String ANNOTATION_ACTION = "ANNOTATION_ACTION";
    static final String MEDIA_ANNOTATION = "MEDIA_ANNOTATION";

    private static final Set<String> MEDIA_ANNOTATION_SUBTYPES =
            Set.of("RichMedia", "3D", "Screen", "Movie", "Sound");

    private final EvidenceProperties properties;

    public PdfCdrRenderer(EvidenceProperties properties) {
        this.properties = properties;
    }

    public Flattened flatten(byte[] source) {
        try (PDDocument document = Loader.loadPDF(source)) {
            if (document.isEncrypted()) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.POLICY,
                        "EVIDENCE_PDF_ENCRYPTED_DENIED");
            }
            int pages = document.getNumberOfPages();
            if (pages < 1) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.INTEGRITY,
                        "EVIDENCE_PDF_PARSE_FAILED");
            }
            if (pages > properties.getProcessor().getMaxPdfPages()) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.POLICY,
                        "EVIDENCE_PDF_PAGE_LIMIT");
            }
            int dpi = properties.getProcessor().getPdfRenderDpi();
            long pixelCap = properties.getProcessor().getMaxDecodedImagePixels();
            for (PDPage page : document.getPages()) {
                requireRenderablePixelBudget(page, dpi, pixelCap);
            }

            List<String> disarmed = inspect(document);
            byte[] rebuilt = rebuild(document, dpi);
            return new Flattened(rebuilt, disarmed, pages);
        } catch (EvidenceProcessor.ProcessingException error) {
            throw error;
        } catch (Exception error) {
            // Malformed cross-reference tables, cyclic object graphs, truncated streams —
            // the parser refusing is the defence working, and the row fails closed.
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.INTEGRITY,
                    "EVIDENCE_PDF_PARSE_FAILED",
                    error);
        }
    }

    /** The declared canvas is checked before any decoding — the #2860 lesson. */
    private static void requireRenderablePixelBudget(PDPage page, int dpi, long pixelCap) {
        PDRectangle box = page.getMediaBox();
        double scale = dpi / 72.0;
        long width = (long) Math.ceil(box.getWidth() * scale);
        long height = (long) Math.ceil(box.getHeight() * scale);
        if (width <= 0 || height <= 0 || width * height > pixelCap) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.POLICY,
                    "EVIDENCE_IMAGE_DECOMPRESSION_LIMIT");
        }
    }

    /**
     * Names what the reconstruction will drop. Detection failures are not swallowed into an
     * empty list — an inspection that cannot complete fails the row, because "no findings"
     * must always mean "inspected and none found".
     */
    private List<String> inspect(PDDocument document) {
        try {
            Set<String> findings = new LinkedHashSet<>();
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            if (catalog.getOpenAction() instanceof PDAction action) {
                findings.add(OPEN_ACTION);
                classifyAction(action.getCOSObject(), findings);
            }
            if (catalog.getNames() != null) {
                if (catalog.getNames().getJavaScript() != null) findings.add(JAVASCRIPT);
                if (catalog.getNames().getEmbeddedFiles() != null) findings.add(EMBEDDED_FILE);
            }
            if (catalog.getAcroForm() != null && catalog.getAcroForm().hasXFA()) {
                findings.add(XFA_FORM);
            }
            COSDictionary catalogDict = catalog.getCOSObject();
            if (catalogDict.getDictionaryObject(COSName.AA) instanceof COSDictionary aa
                    && aa.size() > 0) {
                findings.add(ADDITIONAL_ACTION);
            }
            for (PDPage page : document.getPages()) {
                if (page.getCOSObject().getDictionaryObject(COSName.AA) instanceof COSDictionary aa
                        && aa.size() > 0) {
                    findings.add(ADDITIONAL_ACTION);
                }
                for (PDAnnotation annotation : page.getAnnotations()) {
                    String subtype = annotation.getSubtype();
                    if ("FileAttachment".equals(subtype)) findings.add(EMBEDDED_FILE);
                    if (subtype != null && MEDIA_ANNOTATION_SUBTYPES.contains(subtype)) {
                        findings.add(MEDIA_ANNOTATION);
                    }
                    COSDictionary annotationDict = annotation.getCOSObject();
                    if (annotationDict.getDictionaryObject(COSName.A) instanceof COSDictionary action) {
                        classifyAction(action, findings);
                    }
                    if (annotationDict.getDictionaryObject(COSName.AA) instanceof COSDictionary aa
                            && aa.size() > 0) {
                        findings.add(ADDITIONAL_ACTION);
                    }
                }
            }
            return new ArrayList<>(findings);
        } catch (EvidenceProcessor.ProcessingException error) {
            throw error;
        } catch (Exception error) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.SANITIZE_FAILED,
                    "EVIDENCE_PDF_INSPECTION_FAILED",
                    error);
        }
    }

    private static void classifyAction(COSDictionary action, Set<String> findings) {
        String type = action.getNameAsString(COSName.S);
        if (type == null) {
            findings.add(ANNOTATION_ACTION);
            return;
        }
        switch (type) {
            case "JavaScript" -> findings.add(JAVASCRIPT);
            case "Launch" -> findings.add(LAUNCH_ACTION);
            case "SubmitForm", "ImportData", "ResetForm" -> findings.add(FORM_ACTION);
            // GoTo/URI links carry no execution; a flattened page loses them anyway and
            // recording every hyperlink as a "finding" would bury the real ones.
            case "GoTo", "GoToR", "URI" -> { }
            default -> findings.add(ANNOTATION_ACTION);
        }
    }

    /** A new document containing only page rasters. Nothing survives by omission. */
    private static byte[] rebuild(PDDocument source, int dpi) throws Exception {
        PDFRenderer renderer = new PDFRenderer(source);
        try (PDDocument output = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int index = 0; index < source.getNumberOfPages(); index++) {
                BufferedImage rendered = renderer.renderImageWithDPI(index, dpi, ImageType.RGB);
                float widthPoints = rendered.getWidth() * 72f / dpi;
                float heightPoints = rendered.getHeight() * 72f / dpi;
                PDPage page = new PDPage(new PDRectangle(widthPoints, heightPoints));
                output.addPage(page);
                PDImageXObject image = JPEGFactory.createFromImage(output, rendered, 0.85f);
                try (PDPageContentStream content = new PDPageContentStream(output, page)) {
                    content.drawImage(image, 0, 0, widthPoints, heightPoints);
                }
            }
            output.save(bytes);
            return bytes.toByteArray();
        }
    }

    public record Flattened(byte[] content, List<String> disarmed, int pageCount) {}
}
