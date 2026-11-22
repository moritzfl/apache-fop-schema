package org.apachefopschema;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.xml.transform.TransformerException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Element;

import static org.apachefopschema.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests that inspect actual FOP output rather than only checking for renderer success. */
class PdfOutputTest {
    @Test
    void navigationResolvesPagesOutlineAndLinks() throws Exception {
        try (PDDocument pdf = Loader.loadPDF(renderExample("navigation", "navigation/document.fo", null))) {
            assertEquals(2, pdf.getNumberOfPages());
            assertEquals(595.275f, pdf.getPage(0).getMediaBox().getWidth(), 0.01f);
            assertEquals(841.889f, pdf.getPage(0).getMediaBox().getHeight(), 0.01f);
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Report overview") && text.contains("Report results"), text);
            assertTrue(text.contains("Page 1 of 2") && text.contains("Page 2 of 2"), text);
            var catalog = pdf.getDocumentCatalog();
            var outline = catalog.getDocumentOutline().getFirstChild();
            assertEquals("Results outline", outline.getTitle());
            assertEquals(pdf.getPage(1).getCOSObject(), outline.findDestinationPage(pdf).getCOSObject());
            var named = catalog.getNames().getDests().getValue("results");
            assertEquals(1, named.retrievePageNumber());
            var links = pdf.getPage(0).getAnnotations().stream().map(PDAnnotationLink.class::cast).toList();
            assertEquals(2, links.size());
            var internal = assertInstanceOf(PDActionGoTo.class, links.get(0).getAction());
            assertEquals(1, assertInstanceOf(PDPageDestination.class, internal.getDestination()).retrievePageNumber());
            assertEquals("https://example.org/", assertInstanceOf(PDActionURI.class, links.get(1).getAction()).getURI());
        }
    }

    @Test
    void taggedPdfRetainsXmpInfoPreferencesAndAccessibility() throws Exception {
        byte[] bytes = renderExample("tagged-metadata", "tagged-metadata/document.fo", "tagged-metadata/fop.xconf");
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            assertEquals(1, pdf.getNumberOfPages());
            assertSize(pdf.getPage(0).getMediaBox(), 320, 400);
            assertEquals(1.7f, pdf.getVersion());
            assertNull(pdf.getPage(0).getContentStreams().next().getCOSObject().getFilters());
            var info = pdf.getDocumentInformation();
            assertEquals("Field report", info.getTitle());
            assertEquals("Example Team", info.getAuthor());
            assertEquals("Illustrated observations", info.getSubject());
            assertEquals("Schema verification", info.getCustomMetadataValue("Project"));
            var catalog = pdf.getDocumentCatalog();
            assertEquals("en-GB", catalog.getLanguage());
            assertEquals("DuplexFlipShortEdge", catalog.getViewerPreferences().getDuplex());
            assertTrue(catalog.getMarkInfo().isMarked());
            var tags = tags(catalog.getStructureTreeRoot());
            assertTrue(tags.stream().anyMatch(tag -> "H1".equals(tag.getStructureType())));
            assertTrue(tags.stream().anyMatch(tag -> "Figure".equals(tag.getStructureType())
                    && "Blue square".equals(tag.getAlternateDescription())));
            assertTrue(tags.stream().anyMatch(tag -> "World Wide Web".equals(tag.getExpandedForm())));
            assertEquals("Back to the report title", pdf.getPage(0).getAnnotations().getFirst().getContents());
            try (var xmp = catalog.getMetadata().exportXMPMetadata()) {
                Element metadata = parse(xmp.readAllBytes()).getDocumentElement();
                var title = (Element) metadata.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "title").item(0);
                var translations = title.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li");
                assertEquals(2, translations.getLength());
                assertEquals("Feldbericht", translations.item(1).getTextContent());
            }
        }
    }

    @Test
    void bareRdfPacketAndSimpleXmlLangAreAccepted() throws Exception {
        String fo = example("tagged-metadata/document.fo").replace("<x:xmpmeta>", "").replace("</x:xmpmeta>", "")
                .replace("language=\"en\" country=\"GB\"", "xml:lang=\"en-GB\"");
        validate(schema("fo.xsd"), fo);
        var result = render(fo, example("tagged-metadata/fop.xconf"), EXAMPLES);
        result.assertNoProblems();
        try (var pdf = Loader.loadPDF(result.bytes())) {
            assertEquals("Field report", pdf.getDocumentInformation().getTitle());
            assertEquals("en-GB", pdf.getDocumentCatalog().getLanguage());
        }
    }

    @ParameterizedTest
    @CsvSource({"doubly-even,4", "doubly-odd,3", "end-on-doubly-even,3", "end-on-doubly-odd,2"})
    void duplexPageCountDistinguishesSequenceLengthFromFinalPageNumber(String value, int pageCount) throws Exception {
        String fo = example("navigation/document.fo")
                .replace("force-page-count=\"no-force\"", "initial-page-number=\"2\" force-page-count=\"" + value + "\"");
        validate(schema("fo.xsd"), fo);
        var result = render(fo, null, EXAMPLES);
        result.assertNoProblems();
        try (var pdf = Loader.loadPDF(result.bytes())) {
            assertEquals(pageCount, pdf.getNumberOfPages());
        }
    }

    @Test
    void prepressPageBoxesAndCaptionAreInTheOutput() throws Exception {
        try (PDDocument pdf = Loader.loadPDF(renderExample("print-table", "print-table/document.fo", null))) {
            var page = pdf.getPage(0);
            assertSize(page.getTrimBox(), 200, 300);
            assertSize(page.getBleedBox(), 208, 308);
            assertSize(page.getMediaBox(), 216, 316);
            assertSameBox(page.getTrimBox(), page.getCropBox());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Sales figures") && text.contains("November") && text.contains("35 units"), text);
            assertTrue(text.indexOf("Sales figures") < text.indexOf("November"), text);
        }
    }

    @Test
    void runningHeaderCarriesOverAndExternalSvgAppendsAWholePage() throws Exception {
        byte[] bytes = renderExample("running-header", "running-header/document.fo", null);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertEquals(3, pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            assertEquals(2, text.lines().filter(line -> line.equals("Chapter: Observations")).count(), text);
            assertSize(pdf.getPage(2).getMediaBox(), 90, 60);
            var renderer = new PDFRenderer(pdf);
            var appendix = renderer.renderImageWithDPI(2, 72);
            assertEquals(0x1d58a0, appendix.getRGB(45, 30) & 0xffffff);
            var firstPage = renderer.renderImageWithDPI(0, 72);
            boolean hasRed = false;
            for (int y = 0; y < firstPage.getHeight() && !hasRed; y++) {
                for (int x = 0; x < firstPage.getWidth() && !hasRed; x++) {
                    hasRed = (firstPage.getRGB(x, y) & 0xffffff) == 0xff0000;
                }
            }
            assertTrue(hasRed, "Change bar should produce visible red pixels");
        }
    }

    @Test
    void encryptionRequiresPasswordAndSetsPermissions() throws Exception {
        byte[] bytes = renderExample("encrypted", "navigation/document.fo", "encrypted/fop.xconf");
        assertThrows(InvalidPasswordException.class, () -> Loader.loadPDF(bytes));
        try (var pdf = Loader.loadPDF(bytes, "example-user")) {
            assertTrue(pdf.isEncrypted());
            assertEquals(256, pdf.getEncryption().getLength());
            assertFalse(pdf.getCurrentAccessPermission().canPrint());
            assertFalse(pdf.getCurrentAccessPermission().canExtractContent());
            assertTrue(new PDFTextStripper().getText(pdf).contains("Report overview"));
        }
        try (var pdf = Loader.loadPDF(bytes, "example-owner")) {
            assertTrue(pdf.getCurrentAccessPermission().isOwnerPermission());
        }
    }

    @Test
    void namespaceQualifiedConfigValidatesButOnlyUnprefixedNamesTakeEffect() throws Exception {
        String fo = example("tagged-metadata/document.fo");
        String prefixed = example("namespaced-config/prefixed.xconf");
        String unprefixed = example("namespaced-config/fop.xconf");
        var namespaced = schema("fop-config.xsd");
        validate(namespaced, unprefixed);
        validate(namespaced, prefixed);
        try (var pdf = Loader.loadPDF(render(fo, unprefixed, EXAMPLES).bytes())) {
            assertSize(pdf.getPage(0).getMediaBox(), 350, 450);
            assertTrue(pdf.getDocumentCatalog().getMarkInfo().isMarked());
        }
        try (var pdf = Loader.loadPDF(render(fo, prefixed, EXAMPLES).bytes());
             var defaults = Loader.loadPDF(render(fo, null, EXAMPLES).bytes())) {
            assertSameBox(defaults.getPage(0).getMediaBox(), pdf.getPage(0).getMediaBox());
            assertNull(pdf.getDocumentCatalog().getStructureTreeRoot());
        }
        for (String xml : new String[]{prefixed, unprefixed}) {
            String normalized = transform("normalize-fop-config.xsl", xml);
            validate(schema("fop-config-no-namespace.xsd"), normalized);
            assertFalse(normalized.contains("schemaLocation"));
            try (var pdf = Loader.loadPDF(render(fo, normalized, EXAMPLES).bytes())) {
                assertSize(pdf.getPage(0).getMediaBox(), 350, 450);
                assertTrue(pdf.getDocumentCatalog().getMarkInfo().isMarked());
            }
        }
    }

    @Test
    void standaloneStrictFopNeedsFoSchemaHintsRemoved() throws Exception {
        String fo = example("navigation/document.fo");
        var failure = assertThrows(TransformerException.class, () -> render(fo, null, EXAMPLES, false));
        assertTrue(failure.getMessage().contains("xsi:schemaLocation"), failure::getMessage);
        String stripped = transform("strip-fo-schema-hints.xsl", fo);
        validate(schema("fo.xsd"), stripped);
        var rendered = render(stripped, null, EXAMPLES, false);
        rendered.assertNoProblems();
        try (var pdf = Loader.loadPDF(rendered.bytes())) {
            assertEquals(2, pdf.getNumberOfPages());
        }
    }

    @Test
    void declaredCaptionSideDoesNotMoveCaptionInFop212() throws Exception {
        String fo = example("print-table/document.fo").replace("<fo:table-and-caption>", "<fo:table-and-caption caption-side=\"after\">");
        validate(schema("fo.xsd"), fo);
        try (var pdf = Loader.loadPDF(render(fo, null, EXAMPLES).bytes())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Sales figures") && text.indexOf("Sales figures") < text.indexOf("November"), text);
        }
    }

    @Test
    void objectStreamsAreWrittenWhenEnabled() throws Exception {
        String config = """
                <fop version="1.0"><renderers><renderer mime="application/pdf">
                    <version>1.7</version><use-object-streams>true</use-object-streams>
                </renderer></renderers></fop>
                """;
        validate(schema("fop-config-no-namespace.xsd"), config);
        var result = render(example("navigation/document.fo"), config, EXAMPLES);
        result.assertNoProblems();
        writePdf("object-streams", result.bytes());
        assertTrue(new String(result.bytes(), StandardCharsets.ISO_8859_1).contains("/Type /ObjStm"));
        try (var pdf = Loader.loadPDF(result.bytes())) {
            assertTrue(pdf.getDocument().isXRefStream());
            assertTrue(new PDFTextStripper().getText(pdf).contains("Report overview"));
        }
    }

    @Test
    void pdfUaIdentificationAndFullVersusSubsetEmbedding(@TempDir Path assets) throws Exception {
        String fo = example("pdf-ua/document.fo");
        String config = example("pdf-ua/fop.xconf");
        validate(schema("fo.xsd"), fo);
        validate(schema("fop-config-no-namespace.xsd"), config);
        // Use the freely licensed font already bundled with the pinned PDFBox release.
        try (var font = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertNotNull(font, "Missing Liberation Sans in PDFBox");
            Files.copy(font, assets.resolve("LiberationSans-Regular.ttf"));
        }
        var full = render(fo, config, assets);
        var subset = render(fo, config.replace("embedding-mode=\"full\"", "embedding-mode=\"subset\""), assets);
        full.assertNoProblems();
        subset.assertNoProblems();
        writePdf("pdf-ua-full", full.bytes());
        writePdf("pdf-ua-subset", subset.bytes());
        try (var fullPdf = Loader.loadPDF(full.bytes()); var subsetPdf = Loader.loadPDF(subset.bytes())) {
            var catalog = fullPdf.getDocumentCatalog();
            assertTrue(catalog.getMarkInfo().isMarked());
            assertEquals("en-GB", catalog.getLanguage());
            try (var xmp = catalog.getMetadata().exportXMPMetadata()) {
                var part = parse(xmp.readAllBytes()).getElementsByTagNameNS("http://www.aiim.org/pdfua/ns/id/", "part");
                assertEquals(1, part.getLength());
                assertEquals("1", part.item(0).getTextContent());
            }
            PDFont fullFont = onlyFont(fullPdf), subsetFont = onlyFont(subsetPdf);
            assertTrue(fullFont.isEmbedded());
            assertTrue(subsetFont.isEmbedded());
            assertFalse(fullFont.getName().matches("[A-Z]{6}\\+.+"), fullFont::getName);
            assertTrue(subsetFont.getName().matches("[A-Z]{6}\\+.+"), subsetFont::getName);
            try (var fullProgram = fullFont.getFontDescriptor().getFontFile2().createInputStream();
                 var subsetProgram = subsetFont.getFontDescriptor().getFontFile2().createInputStream()) {
                assertTrue(fullProgram.readAllBytes().length > subsetProgram.readAllBytes().length);
            }
            assertTrue(new PDFTextStripper().getText(fullPdf).contains("Accented text: café"));
            assertTrue(new PDFTextStripper().getText(subsetPdf).contains("Accented text: café"));
        }
    }

    private static ArrayList<PDStructureElement> tags(PDStructureNode root) {
        var result = new ArrayList<PDStructureElement>();
        for (Object kid : root.getKids()) {
            if (kid instanceof PDStructureElement element) {
                result.add(element);
                result.addAll(tags(element));
            }
        }
        return result;
    }

    private static void assertSize(PDRectangle rect, float width, float height) {
        assertEquals(width, rect.getWidth(), 0.01f);
        assertEquals(height, rect.getHeight(), 0.01f);
    }

    private static void assertSameBox(PDRectangle expected, PDRectangle actual) {
        assertArrayEquals(new float[]{expected.getLowerLeftX(), expected.getLowerLeftY(),
                        expected.getUpperRightX(), expected.getUpperRightY()},
                new float[]{actual.getLowerLeftX(), actual.getLowerLeftY(), actual.getUpperRightX(), actual.getUpperRightY()},
                0.01f);
    }

    private static PDFont onlyFont(PDDocument pdf) throws Exception {
        var resources = pdf.getPage(0).getResources();
        var fontNames = resources.getFontNames().iterator();
        var font = resources.getFont(fontNames.next());
        assertFalse(fontNames.hasNext(), "Expected one registered font");
        return font;
    }
}
