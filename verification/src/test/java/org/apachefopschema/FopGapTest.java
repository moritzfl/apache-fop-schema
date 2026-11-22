package org.apachefopschema;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPathFactory;

import org.apache.fop.Version;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.fo.Constants;
import org.apache.fop.fo.ElementMapping;
import org.apache.fop.fo.FOElementMapping;
import org.apache.fop.fo.FOPropertyMapping;
import org.apache.fop.fo.UnknownXMLObj;
import org.apache.fop.fo.extensions.ExtensionElementMapping;
import org.apache.fop.fo.properties.EnumProperty;
import org.apache.fop.fo.properties.KeepProperty;
import org.apache.fop.fo.properties.NumberProperty;
import org.apache.fop.fo.properties.ToBeImplementedProperty;
import org.apache.fop.layoutmgr.Keep;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionRemoteGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import static org.apachefopschema.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** FOP upgrade probes. An unexpected change is a cue to review the associated XSD annotation. */
class FopGapTest {
    private static final Path IMAGE_BASE = EXAMPLES.resolve("running-header");
    private static final String SAMPLE = "Sample text";
    private static final String TEXT = block("", SAMPLE);
    private static final String CAPTION_SIDE = "caption-side";
    private static final String AUTO_TABLE = "table-layout=\"auto\"";
    private static final String PRECEDENCE = "border-collapse=\"collapse-with-precedence\"";
    private static final Map<String, Executable> PROBES = probes();
    @TempDir
    static Path assets;

    @TestFactory
    Stream<DynamicTest> recordedGaps() throws Exception {
        assertEquals(System.getProperty("fopVersion"), Version.getVersion(), "Resolved FOP runtime");
        var expected = CompatibilityReport.read(SCHEMAS).stream()
                .filter(gap -> !gap.status().equals("not-modeled"))
                .map(CompatibilityReport.Gap::feature)
                .collect(Collectors.toCollection(TreeSet::new));
        var missing = expected.stream().filter(feature -> !PROBES.containsKey(feature)).toList();
        var obsolete = PROBES.keySet().stream().filter(feature -> !expected.contains(feature)).sorted().toList();
        assertTrue(missing.isEmpty() && obsolete.isEmpty(), () -> "Missing probes: " + missing + "; obsolete probes: " + obsolete);
        return expected.stream().map(feature -> DynamicTest.dynamicTest(feature, PROBES.get(feature)));
    }

    private static Map<String, Executable> probes() {
        var probes = new LinkedHashMap<String, Executable>();
        registerMissingElements(probes);
        registerPropertyStubs(probes);
        registerLayoutProbes(probes);
        registerWarningsAndFailures(probes);
        registerPdfProbes(probes);
        registerPageAndBlockProbes(probes);
        return Map.copyOf(probes);
    }

    /** FOP's actual element registry, not the repository XSD. Registration changes warrant review. */
    private static void registerMissingElements(Map<String, Executable> probes) {
        for (String name : List.of(
                "flow-assignment", "flow-map", "flow-name-specifier", "flow-source-list", "flow-target-list",
                "folio-prefix", "folio-suffix", "index-key-reference", "index-page-citation-list",
                "index-page-citation-list-separator", "index-page-citation-range-separator", "index-page-number-prefix",
                "index-page-number-suffix", "index-range-begin", "index-range-end", "page-sequence-wrapper",
                "region-name-specifier", "scaling-value-citation")) {
            add(probes, "fo:" + name, () -> assertUnmapped(FOElementMapping.URI, name));
        }
        for (String name : List.of("accessibility", "form-field", "watermark", "pdf-security")) {
            add(probes, "fox:" + name, () -> assertUnmapped(ExtensionElementMapping.URI, name));
        }
        add(probes, "fox:document-info / fox:document-info-entry", () -> {
            assertUnmapped(ExtensionElementMapping.URI, "document-info");
            assertUnmapped(ExtensionElementMapping.URI, "document-info-entry");
        });
        add(probes, "fox:outline / fox:label", () -> {
            assertUnmapped(ExtensionElementMapping.URI, "outline");
            assertUnmapped(ExtensionElementMapping.URI, "label");
        });
    }

    private static void assertUnmapped(String namespace, String name) {
        var registry = FopFactory.newInstance(EXAMPLES.toUri()).getElementMappingRegistry();
        var table = registry.getElementMapping(namespace).getTable();
        var maker = table.getOrDefault(name, table.get(ElementMapping.DEFAULT));
        assertTrue(maker == null || maker instanceof UnknownXMLObj.Maker,
                () -> "FOP now has an element implementation for " + name + "; check its behavior");
    }

    private static void registerPropertyStubs(Map<String, Executable> probes) {
        for (String name : List.of("clip", "text-shadow", "glyph-orientation-horizontal", "glyph-orientation-vertical",
                "active-state", "auto-restore", "case-name", "case-title", "indicate-destination",
                "destination-placement-offset", "target-processing-context", "target-presentation-context",
                "target-stylesheet")) {
            add(probes, name, () -> assertPropertyStub(name));
        }
        add(probes, "size shorthand", () -> assertPropertyStub("size"));
        add(probes, "background shorthand", () -> assertPropertyStub("background"));
        add(probes, "fox:block-progression-unit", () -> assertPropertyStub("fox:block-progression-unit"));
        add(probes, "keep-together integer strength", () -> {
            assertTrue(keepPenalty(1) > 0, "A positive keep must incur a break penalty");
            assertEquals(keepPenalty(1), keepPenalty(10), "FOP now distinguishes integer keep penalties");
        });
        add(probes, "Common aural properties", () -> {
            for (String name : List.of("azimuth", "cue", "cue-after", "cue-before", "elevation", "pause",
                    "pause-after", "pause-before", "pitch", "pitch-range", "play-during", "richness", "speak",
                    "speak-header", "speak-numeral", "speak-punctuation", "speech-rate", "stress", "voice-family",
                    "volume")) {
                assertPropertyStub(name);
            }
        });
    }

    private static int keepPenalty(int strength) {
        var property = new KeepProperty();
        var auto = EnumProperty.getInstance(Constants.EN_AUTO, "AUTO");
        property.setComponent(Constants.CP_WITHIN_LINE, auto, true);
        property.setComponent(Constants.CP_WITHIN_COLUMN, auto, true);
        property.setComponent(Constants.CP_WITHIN_PAGE, NumberProperty.getInstance(strength), false);
        return Keep.getKeep(property).getPenalty();
    }

    private static void assertPropertyStub(String name) {
        int id = FOPropertyMapping.getPropertyId(name);
        assertTrue(id < 0 || FOPropertyMapping.getGenericMappings()[id] instanceof ToBeImplementedProperty.Maker,
                () -> "FOP replaced the missing/stub property " + name + "; check its behavior");
    }

    /** Compare laid-out areas with a control rather than just detecting that XML parsed. */
    private static void registerLayoutProbes(Map<String, Executable> probes) {
        for (String[] entry : new String[][] {
                {"font-size-adjust", "font-size-adjust=\"1.2\""},
                {"font-stretch", "font-stretch=\"ultra-expanded\""},
                {"font-variant", "font-variant=\"small-caps\""},
        }) {
            add(probes, entry[0], () -> assertSameLayout(document(TEXT), document(block(entry[1], SAMPLE))));
        }
        add(probes, "direction", () -> assertSameLayout(
                document(inline("direction=\"ltr\" unicode-bidi=\"bidi-override\"")),
                document(inline("direction=\"rtl\" unicode-bidi=\"bidi-override\""))));
        add(probes, "unicode-bidi", () -> assertSameLayout(
                document(inline("direction=\"rtl\" unicode-bidi=\"normal\"")),
                document(inline("direction=\"rtl\" unicode-bidi=\"bidi-override\""))));
        add(probes, "relative-position", () -> {
            TextPosition control = glyph(document(TEXT), "S");
            TextPosition offset = glyph(document(block("relative-position=\"relative\" top=\"15pt\" left=\"14pt\"", SAMPLE)), "S");
            assertEquals(control.getX(), offset.getX(), 0.01, "Relative position now changes the PDF x coordinate");
            assertEquals(control.getY(), offset.getY(), 0.01, "Relative position now changes the PDF y coordinate");
        });
        add(probes, "relative-align", () -> {
            String row = "<fo:table-row><fo:table-cell %s>" + block("font-size=\"8pt\"", "Small") + "</fo:table-cell>"
                    + "<fo:table-cell %s>" + block("font-size=\"24pt\"", "Large") + "</fo:table-cell></fo:table-row>";
            String before = "relative-align=\"before\"";
            String baseline = "relative-align=\"baseline\"";
            assertSameLayout(document(twoColumnTable(row.formatted(before, before))),
                    document(twoColumnTable(row.formatted(baseline, baseline))));
        });
        add(probes, "media-usage", () -> assertSameLayout(document(TEXT), document("media-usage=\"unbounded\"",
                "page-width=\"180pt\" page-height=\"140pt\"", "<fo:region-body/>", "", "", TEXT)));
        add(probes, "fo:initial-property-set", () -> assertSameLayout(document(TEXT),
                document(block("", "<fo:initial-property-set font-size=\"28pt\" color=\"red\"/>" + SAMPLE))));
        add(probes, "treat-as-word-space", () -> compareCharacter("treat-as-word-space=\"true\"", "X"));
        add(probes, "suppress-at-line-break", () -> {
            String body = "<fo:character character=\"-\" suppress-at-line-break=\"%s\"/>Leading glyph";
            String setup = "white-space-treatment=\"ignore-if-after-linefeed\"";
            assertSameLayout(document(block(setup, body.formatted("retain"))),
                    document(block(setup, body.formatted("suppress"))));
        });
        add(probes, "text-altitude", () -> compareCharacter("text-altitude=\"50pt\"", "X"));
        add(probes, "text-depth", () -> compareCharacter("text-depth=\"50pt\"", "X"));
        add(probes, "score-spaces", () -> assertSameLayout(
                document(block("", "<fo:inline text-decoration=\"underline\">One two</fo:inline>")),
                document(block("", "<fo:inline text-decoration=\"underline\" score-spaces=\"false\">One two</fo:inline>"))));
        add(probes, "line-stacking-strategy=\"line-height\"", () -> {
            String mixed = "Regular <fo:inline font-size=\"25pt\">Large</fo:inline> text";
            assertSameLayout(document(block("line-height=\"12pt\"", mixed)),
                    document(block("line-height=\"12pt\" line-stacking-strategy=\"line-height\"", mixed)));
        });
        add(probes, "font shorthand", () -> {
            String base = "font-family=\"Courier\" font-size=\"21pt\"";
            assertSameLayout(document(block(base, "<fo:inline>" + SAMPLE + "</fo:inline>")),
                    document(block(base, "<fo:inline font=\"caption\">" + SAMPLE + "</fo:inline>")));
        });
        add(probes, "display-align", () -> {
            // FOP positions smaller content inside block-containers, not this inline-container.
            String box = "Before <fo:inline-container inline-progression-dimension=\"55pt\""
                    + " block-progression-dimension=\"30pt\" display-align=\"%s\">"
                    + block("", "Box") + "</fo:inline-container> after";
            assertSameLayout(document(block("", box.formatted("before"))), document(block("", box.formatted("after"))));
        });
        add(probes, "wrap-option", () -> {
            String longText = "one two three four five six seven eight nine ten";
            String box = "<fo:block-container width=\"52pt\">" + block("", "<fo:inline %s>" + longText
                    + "</fo:inline>") + "</fo:block-container>";
            assertSameLayout(document(box.formatted("")), document(box.formatted("wrap-option=\"no-wrap\"")));
        });
        for (String property : List.of("text-align", "text-align-last")) {
            add(probes, property, () -> {
                String setup = property.equals("text-align-last") ? "text-align=\"justify\" " : "";
                String start = block(setup + property + "=\"start\"", SAMPLE);
                String inside = block(setup + property + "=\"inside\"", SAMPLE);
                assertSameLayout(onPageTwo(start), onPageTwo(inside));
                String end = block(setup + property + "=\"end\"", SAMPLE);
                String outside = block(setup + property + "=\"outside\"", SAMPLE);
                assertSameLayout(onPageTwo(end), onPageTwo(outside));
            });
        }
        add(probes, "font-weight=\"bolder\" / \"lighter\"", () -> {
            String sample = "<fo:inline font-weight=\"%s\">Inside</fo:inline>";
            String normal = "font-family=\"Helvetica\" font-weight=\"400\"";
            String bold = "font-family=\"Helvetica\" font-weight=\"700\"";
            String regularFace = glyph(document(block(normal, sample.formatted("400"))), "I").getFont().getName();
            String boldFace = glyph(document(block(bold, sample.formatted("700"))), "I").getFont().getName();
            assertNotEquals(regularFace, boldFace, "Control must have distinct normal and bold faces");
            assertEquals(regularFace, glyph(document(block(normal, sample.formatted("bolder"))), "I").getFont().getName(),
                    "Relative bolder now selects the next available face");
            assertEquals(boldFace, glyph(document(block(bold, sample.formatted("lighter"))), "I").getFont().getName(),
                    "Relative lighter now selects the previous available face");
        });
        for (String property : List.of("keep-with-next", "keep-with-previous")) {
            add(probes, property, () -> {
                String body = "Prefix <fo:inline %s>Alpha</fo:inline> <fo:inline %s>Bravo</fo:inline>";
                String control = narrow(body.formatted("", ""));
                String keep = property + ".within-line=\"always\"";
                String treatment = narrow(property.equals("keep-with-next") ? body.formatted(keep, "") : body.formatted("", keep));
                assertTrue(glyph(control, "B").getYDirAdj() > glyph(control, "A").getYDirAdj(),
                        "Control must break between the two inlines");
                assertTrue(glyph(treatment, "B").getYDirAdj() > glyph(treatment, "A").getYDirAdj(),
                        "FOP now keeps the two inlines on the same line");
            });
        }
        add(probes, "hyphenation-keep", () -> {
            String body = "<fo:block font-family=\"Courier\" font-size=\"10pt\" line-height=\"12pt\" "
                    + "orphans=\"1\" widows=\"1\" hyphenation-keep=\"%s\">Hi abc\u00addefgh</fo:block>";
            String control = document("", "page-width=\"70pt\" page-height=\"32pt\"", "<fo:region-body/>", "",
                    "", body.formatted("auto"));
            try (var pdf = Loader.loadPDF(render(control, null, IMAGE_BASE).bytes())) {
                var text = new PDFTextStripper();
                text.setEndPage(1);
                assertTrue(text.getText(pdf).contains("abc-"), "Control must hyphenate on the last line of the page");
            }
            assertSameLayout(control, control.replace("hyphenation-keep=\"auto\"", "hyphenation-keep=\"page\""));
        });
        add(probes, "content-type", () -> assertSameLayout(
                document(graphic("")), document(graphic("content-type=\"content-type:application/octet-stream\""))));
        add(probes, "scaling-method", () -> {
            ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", assets.resolve("sample.png").toFile());
            String graphic = "<fo:external-graphic src=\"url('sample.png')\" content-width=\"25pt\" %s/>";
            assertSameLayout(document(block("", graphic.formatted(""))),
                    document(block("", graphic.formatted("scaling-method=\"integer-pixels\""))), assets, true);
        });
        add(probes, "leader-alignment", () -> assertSameLayout(
                document(leader("")), document(leader("leader-alignment=\"reference-area\""))));
        add(probes, "z-index", () -> {
            String box = "<fo:block-container absolute-position=\"absolute\" left=\"10pt\" top=\"10pt\""
                    + " width=\"60pt\" height=\"30pt\" %s>%s</fo:block-container>";
            assertSameLayout(document(box.formatted("", block("", "First")) + box.formatted("", block("", "Second"))),
                    document(box.formatted("z-index=\"20\"", block("", "First")) + box.formatted("", block("", "Second"))));
        });
        add(probes, "page-height=\"indefinite\"", () -> assertIndefiniteDimensionIsZero("height", 3));
        add(probes, "page-width=\"indefinite\"", () -> assertIndefiniteDimensionIsZero("width", 2));
        add(probes, "background-position-horizontal", () -> compareRepeatingBackground("repeat-x",
                "background-position-horizontal=\"17pt\""));
        add(probes, "background-position-vertical", () -> compareRepeatingBackground("repeat-y",
                "background-position-vertical=\"17pt\""));
        add(probes, "background-attachment", () -> {
            String image = "background-image=\"url('appendix.svg')\" background-repeat=\"no-repeat\"";
            assertSameLayout(document(block(image, SAMPLE)), document(block(image + " background-attachment=\"fixed\"", SAMPLE)));
        });
        add(probes, "rendering-intent on fo:color-profile", () -> {
            Files.write(assets.resolve("profile.icc"), ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB).getData());
            String declaration = "<fo:declarations><fo:color-profile color-profile-name=\"profile\""
                    + " src=\"profile.icc\" rendering-intent=\"%s\"/></fo:declarations>";
            String body = block("color=\"rgb-icc(0,0,0,profile,0.1,0.2,0.3)\"", SAMPLE);
            String control = document("", "page-width=\"180pt\" page-height=\"140pt\"", "<fo:region-body/>",
                    declaration.formatted("perceptual"), "", body);
            assertSameLayout(control, control.replace("rendering-intent=\"perceptual\"", "rendering-intent=\"relative-colorimetric\""),
                    assets, true);
        });
        for (String edge : List.of("after", "before", "end", "start")) {
            String property = "border-" + edge + "-precedence";
            add(probes, property, () -> {
                String table = tableWithoutCaption().replace("<fo:table table-layout", "<fo:table "
                        + PRECEDENCE + " table-layout").replace("<fo:table-cell>", "<fo:table-cell border=\"1pt solid\">");
                assertSameLayout(table, table.replace("<fo:table-cell border=", "<fo:table-cell " + property + "=\"9\" border="));
            });
        }
        add(probes, CAPTION_SIDE, FopGapTest::assertCaptionStillBeforeTable);
        add(probes, "fo:table-and-caption", FopGapTest::assertCaptionStillBeforeTable);
        add(probes, "float=\"before\"", () -> assertEquals("0",
                areaValue(document(floatAndText("before", "")), "count(//beforeFloat/*)"),
                "A before float now creates a page-level before-float area"));
        add(probes, "clear", () -> {
            String floats = block("", floatingBox("left", "", "First") + floatingBox("right", "%s", "Second")
                    + " Text beside floats.");
            assertSameLayout(document(floats.formatted("clear=\"none\"")), document(floats.formatted("clear=\"both\"")));
        });
        add(probes, "intrusion-displace", () -> assertSameLayout(document(floatAndText("left", "")),
                document(floatAndText("left", "intrusion-displace=\"none\""))));
    }

    private static void registerWarningsAndFailures(Map<String, Executable> probes) {
        add(probes, AUTO_TABLE, () -> assertTableFallback(AUTO_TABLE));
        add(probes, PRECEDENCE, () -> assertTableFallback(PRECEDENCE));
        add(probes, "fo:table", () -> assertTableFallback(AUTO_TABLE));
        add(probes, "fo:float", () -> {
            String content = block("", floatingBox("left", "", "Side box") + "Body text");
            var result = render(document(table("<fo:table-row><fo:table-cell>" + content
                    + "</fo:table-cell></fo:table-row>")), null, IMAGE_BASE);
            assertEvent(result, ".unimplementedFeature", "fo:float");
            try (var pdf = Loader.loadPDF(result.bytes())) {
                String text = new PDFTextStripper().getText(pdf);
                assertTrue(text.contains("Body text"), text);
                assertFalse(text.contains("Side box"), "FOP now paints a float inside a table");
            }
        });
        add(probes, "fo:inline-container", () -> {
            var result = render(document(block("", "Before <fo:inline-container><fo:block>Box</fo:block>"
                    + "</fo:inline-container> after")), null, IMAGE_BASE);
            assertEvent(result, ".inlineContainerAutoIPDNotSupported", null);
        });
        var toggle = new FutureTask<>(() -> {
            String fo = document("<fo:multi-switch><fo:multi-case case-name=\"first\" starting-state=\"show\">"
                    + block("", "<fo:multi-toggle switch-to=\"next\">Toggle</fo:multi-toggle>")
                    + "</fo:multi-case><fo:multi-case case-name=\"next\"><fo:block>Next</fo:block>"
                    + "</fo:multi-case></fo:multi-switch>");
            return render(fo, null, IMAGE_BASE);
        });
        for (String feature : List.of("fo:multi-toggle", "switch-to", "fo:multi-switch")) {
            add(probes, feature, () -> {
                toggle.run();
                assertEvent(toggle.get(), ".unimplementedFeature", "fo:multi-toggle");
            });
        }
        // The two FOP stubs warn only once per JVM. Share the first execution.
        var multiProperties = new FutureTask<>(() -> {
            String fo = document("<fo:multi-properties><fo:multi-property-set active-state=\"hover\" color=\"red\"/>"
                    + "<fo:wrapper/></fo:multi-properties>" + TEXT);
            return render(fo, null, IMAGE_BASE);
        });
        for (String feature : List.of("fo:multi-properties", "fo:multi-property-set")) {
            add(probes, feature, () -> {
                multiProperties.run();
                assertEvent(multiProperties.get(), ".unimplementedFeature", feature);
            });
        }
        add(probes, "fo:bookmark with external-destination", () -> {
            String fo = example("navigation/document.fo").replace("<fo:bookmark internal-destination=\"results\">",
                    "<fo:bookmark external-destination=\"url('https://example.org/')\">");
            var failure = assertThrows(TransformerException.class, () -> render(fo, null, IMAGE_BASE),
                    "FOP now renders an outline with an external destination");
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("DestinationData must contain a ID reference"),
                    failure::getMessage);
        });
        add(probes, "fox:external-document with PDF input", () -> {
            Files.write(assets.resolve("input.pdf"), render(document(TEXT), null, IMAGE_BASE).bytes());
            String fo = document(TEXT).replace("<fo:root ", "<fo:root xmlns:fox=\"" + ExtensionElementMapping.URI + "\" ")
                    .replace("</fo:root>", "<fox:external-document src=\"url('input.pdf')\"/></fo:root>");
            var result = render(fo, null, assets);
            assertEvent(result, ".imageError", "input.pdf");
            try (var pdf = Loader.loadPDF(result.bytes())) {
                assertEquals(1, pdf.getNumberOfPages(), "PDF appendix unexpectedly works without the image plug-in");
            }
        });
    }

    private static void registerPdfProbes(Map<String, Executable> probes) {
        add(probes, "fo:bookmark-title styling", () -> {
            String fo = example("navigation/document.fo").replace("<fo:bookmark-title>",
                    "<fo:bookmark-title color=\"red\" font-weight=\"bold\" font-style=\"italic\">");
            try (var pdf = Loader.loadPDF(render(fo, null, IMAGE_BASE).bytes())) {
                var outline = pdf.getDocumentCatalog().getDocumentOutline().getFirstChild();
                assertEquals("Results outline", outline.getTitle());
                assertFalse(outline.getCOSObject().containsKey(COSName.C), "FOP now sets an outline color");
                assertFalse(outline.isBold(), "FOP now sets bold outline text");
                assertFalse(outline.isItalic(), "FOP now sets italic outline text");
            }
        });
        add(probes, "show-destination", () -> {
            String fo = document(block("", "<fo:basic-link external-destination=\"url('chapter.pdf')\" show-destination=\"new\">PDF</fo:basic-link>"
                    + " <fo:basic-link external-destination=\"url('file:notes.txt')\" show-destination=\"new\">File</fo:basic-link>"
                    + " <fo:basic-link external-destination=\"url('https://example.org/')\" show-destination=\"new\">Web</fo:basic-link>"));
            try (var pdf = Loader.loadPDF(render(fo, null, IMAGE_BASE).bytes())) {
                var links = pdf.getPage(0).getAnnotations();
                var newWindow = COSName.getPDFName("NewWindow");
                var remote = assertInstanceOf(PDActionRemoteGoTo.class, ((PDAnnotationLink) links.get(0)).getAction());
                var launch = assertInstanceOf(PDActionLaunch.class, ((PDAnnotationLink) links.get(1)).getAction());
                var uri = assertInstanceOf(PDActionURI.class, ((PDAnnotationLink) links.get(2)).getAction());
                assertTrue(remote.getCOSObject().getBoolean(newWindow, false));
                assertTrue(launch.getCOSObject().getBoolean(newWindow, false));
                assertFalse(uri.getCOSObject().getBoolean(newWindow, false),
                        "FOP now requests a new window for a regular URI");
            }
        });
        add(probes, "source-document", () -> {
            String fo = example("tagged-metadata/document.fo").replace("role=\"H1\"",
                    "role=\"H1\" source-document=\"url('https://example.org/source-document-probe')\"");
            var result = render(fo, example("tagged-metadata/fop.xconf"), IMAGE_BASE);
            result.assertNoProblems();
            try (var pdf = Loader.loadPDF(result.bytes())) {
                assertTrue(pdf.getDocumentCatalog().getMarkInfo().isMarked());
                assertFalse(new String(result.bytes(), StandardCharsets.ISO_8859_1).contains("source-document-probe"),
                        "The source URI is now stored in the uncompressed tagged PDF");
            }
        });
        add(probes, "xml:lang as an FO shorthand", () -> {
            String fo = example("tagged-metadata/document.fo").replace("language=\"en\" country=\"GB\"", "xml:lang=\"zh-Hant-TW\"");
            var result = render(fo, example("tagged-metadata/fop.xconf"), IMAGE_BASE);
            result.assertNoProblems();
            try (var pdf = Loader.loadPDF(result.bytes())) {
                String language = pdf.getDocumentCatalog().getLanguage();
                assertNotNull(language);
                assertNotEquals("zh-Hant-TW", language, "FOP now preserves script and region subtags");
            }
        });
        add(probes, "fo:page-number-citation", () -> assertCitationDoesNotRelayout("page-number-citation", "target-block"));
        add(probes, "fo:page-number-citation-last", () -> assertCitationDoesNotRelayout("page-number-citation-last", "target-sequence"));
        add(probes, "id as a layout target", () -> {
            String link = block("", "<fo:basic-link internal-destination=\"target\">Go to target</fo:basic-link>");
            String row = "<fo:table-row %s><fo:table-cell>" + block("%s", "Target") + "</fo:table-cell></fo:table-row>";
            String before = link + block("break-before=\"page\"", "Start of next page");
            assertEquals(1, linkTargetPage(document(before + table(row.formatted("", "id=\"target\"")))),
                    "Control link must resolve to page two");
            assertNotEquals(1, linkTargetPage(document(before + table(row.formatted("id=\"target\"", "")))),
                    "FOP now resolves an internal link to the target table row");
        });
        for (String feature : List.of("starting-state on dynamic content", "fo:multi-case")) {
            add(probes, feature, () -> {
                String body = "<fo:multi-switch><fo:multi-case starting-state=\"hide\">" + block("", "First case")
                        + "</fo:multi-case><fo:multi-case starting-state=\"show\">" + block("", "Second case")
                        + "</fo:multi-case></fo:multi-switch>";
                var result = render(document(body), null, IMAGE_BASE);
                try (var pdf = Loader.loadPDF(result.bytes())) {
                    String text = new PDFTextStripper().getText(pdf);
                    assertTrue(text.contains("First case"), "FOP now honors the initially visible multi-case");
                    assertFalse(text.contains("Second case"), "FOP now renders the case marked show");
                }
            });
        }
    }

    private static int linkTargetPage(String fo) throws Exception {
        try (var pdf = Loader.loadPDF(render(fo, null, IMAGE_BASE).bytes())) {
            for (var annotation : pdf.getPage(0).getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionGoTo action
                        && action.getDestination() instanceof PDPageDestination destination) {
                    return destination.retrievePageNumber();
                }
            }
            return -1;
        }
    }

    /** Probes limitations involving competing layout constraints rather than parse-time support. */
    private static void registerPageAndBlockProbes(Map<String, Executable> probes) {
        for (String edge : List.of("before", "after")) {
            String property = "space-" + edge;
            add(probes, property, () -> {
                String box = "<fo:block-container height=\"24pt\" font-size=\"8pt\" line-height=\"10pt\">%s</fo:block-container>";
                String space = property + ".minimum=\"8pt\" " + property + ".optimum=\"14pt\"";
                String spaced = "id=\"spaced\" " + space;
                String fixed = document(box.formatted(block(edge.equals("after") ? spaced : "", "First")
                        + block(edge.equals("before") ? spaced : "", "Second")));
                assertEquals("4000", areaValue(fixed, "//block[@prod-id='spaced']/@" + property),
                        "FOP no longer squeezes this gap to 4pt, below its 8pt minimum");
            });
        }
        add(probes, "white-space-treatment", () -> {
            String text = "<fo:inline white-space-treatment=\"%s\">A B</fo:inline>";
            String control = document(block("font-family=\"Courier\"", text.formatted("preserve")));
            assertSameLayout(control, control.replace("preserve", "ignore"));
            assertNotEquals(glyph(control, "B").getX(), glyph(control.replace(
                    "font-family=\"Courier\"", "font-family=\"Courier\" white-space-treatment=\"ignore\""), "B").getX(),
                    "Applying ignore to the block must remove the space");
        });
        add(probes, "fo:block-container", () -> {
            String box = "<fo:block-container id=\"box\" %s>"
                    + block("line-height=\"12pt\"", "Box").repeat(3) + "</fo:block-container>";
            String viewport = "(//block[@prod-id='box' and @is-viewport-area='true'])[1]/@bpd";
            assertEquals("36000", areaValue(document(box.formatted("")), viewport), "Auto height fits three lines");
            assertEquals("36000", areaValue(document(box.formatted("block-progression-dimension.maximum=\"25pt\"")), viewport),
                    "FOP now obeys a maximum height with an automatic preferred height");
        });
        add(probes, "fo:simple-page-master", () -> {
            String rows = ("<fo:table-row><fo:table-cell>" + block("", "Row") + "</fo:table-cell></fo:table-row>").repeat(8);
            String fo = document("", "page-width=\"180pt\" page-height=\"80pt\"", "<fo:region-body/>", "",
                    "", table(rows))
                    .replace("</fo:layout-master-set>", """
                        <fo:simple-page-master master-name="narrow" page-width="120pt" page-height="80pt" margin="10pt">
                          <fo:region-body/>
                        </fo:simple-page-master>
                        <fo:page-sequence-master master-name="vary">
                          <fo:single-page-master-reference master-reference="probe"/>
                          <fo:repeatable-page-master-reference master-reference="narrow"/>
                        </fo:page-sequence-master>
                        </fo:layout-master-set>""")
                    .replace("<fo:page-sequence master-reference=\"probe\"", "<fo:page-sequence master-reference=\"vary\"");
            assertEvent(areaTree(fo, IMAGE_BASE), ".nonRestartableContentFlowingToNarrowerPage", null);
        });
        add(probes, "fo:retrieve-table-marker", () -> {
            String tall = "<fo:inline font-size=\"32pt\">Heading</fo:inline>";
            String retrieve = "<fo:retrieve-table-marker retrieve-class-name=\"heading\"/>";
            String fo = example("upgrade-probes/table-marker.fo");
            String headingHeight = "(//block[@prod-id='heading-cell'])[1]/@bpd";
            String control = fo.replace(retrieve, tall);
            assertNotEquals(fo, control);
            assertTrue(Double.parseDouble(areaValue(control, headingHeight)) >= 25000,
                    "A literal heading must reserve room");
            assertTrue(areaValue(fo, "string(//word[.='Heading'])").contains("Heading"), "Marker must be retrieved");
            assertTrue(Double.parseDouble(areaValue(fo, headingHeight)) < 25000,
                    "FOP now reserves space for the taller retrieved heading");
        });
        add(probes, "fo:footnote", () -> {
            String note = "<fo:footnote><fo:inline>*</fo:inline><fo:footnote-body>"
                    + block("", "Note") + block("", "Second note line") + "</fo:footnote-body></fo:footnote>";
            String fo = document("font-size=\"10pt\" line-height=\"12pt\"", "page-width=\"300pt\" page-height=\"104pt\"",
                    "<fo:region-body column-count=\"2\"/>", "", "",
                    block("", "First" + note) + block("", "Body line").repeat(15));
            String overlap = "number(//pageViewport[1]//footnote/@top-offset) - "
                    + "number(//pageViewport[1]//mainReference/span/flow[2]/@bpd)";
            assertTrue(Double.parseDouble(areaValue(fo, overlap)) < 0, "FOP now reserves footnote space in both columns");
        });
    }

    private static String table(String rows) {
        return "<fo:table table-layout=\"fixed\" width=\"100%\">"
                + "<fo:table-column column-width=\"proportional-column-width(1)\"/>"
                + "<fo:table-body>" + rows + "</fo:table-body></fo:table>";
    }

    private static String twoColumnTable(String row) {
        return table(row).replace("<fo:table-body>", "<fo:table-column column-width=\"proportional-column-width(1)\"/><fo:table-body>");
    }

    private static String areaValue(String fo, String expression) throws Exception {
        return XPathFactory.newDefaultInstance().newXPath().evaluate(expression, parse(areaTree(fo, IMAGE_BASE).bytes()));
    }

    private static void assertCitationDoesNotRelayout(String object, String id) throws Exception {
        String citation = block("font-family=\"Courier\" font-size=\"10pt\"", "A <fo:" + object + " ref-id=\"" + id + "\"/> Z");
        String second = "<fo:page-sequence master-reference=\"probe\" id=\"target-sequence\" initial-page-number=\"%s\">"
                + "<fo:flow flow-name=\"xsl-region-body\">" + block("id=\"target-block\"", "Target") + "</fo:flow></fo:page-sequence>";
        String fo = document("", "page-width=\"65pt\" page-height=\"140pt\"", "<fo:region-body/>", "", "", citation);
        String many = fo.replace("</fo:root>", second.formatted("12345") + "</fo:root>");
        try (var pdf = Loader.loadPDF(render(many, null, IMAGE_BASE).bytes())) {
            var text = new PDFTextStripper();
            text.setEndPage(1);
            assertTrue(text.getText(pdf).contains("12345"), "Control must resolve a wider page number");
        }
        String explicit = fo.replace("<fo:" + object + " ref-id=\"" + id + "\"/>", "12345");
        assertTrue(glyph(explicit, "Z").getYDirAdj() > glyph(explicit, "A").getYDirAdj(),
                "Control must wrap the literal page number");
        assertEquals(glyph(many, "A").getYDirAdj(), glyph(many, "Z").getYDirAdj(), 0.01,
                "FOP now rebreaks the line when resolving a wider citation");
    }

    private static void assertIndefiniteDimensionIsZero(String dimension, int boundsIndex) throws Exception {
        String template = "page-width=\"180pt\" page-height=\"140pt\"";
        String control = template.replaceFirst("page-" + dimension + "=\"[^\"]+\"", "page-" + dimension + "=\"auto\"");
        String treatment = control.replace("=\"auto\"", "=\"indefinite\"");
        assertNotEquals("0", viewportBounds(documentWithDimensions(control))[boundsIndex]);
        assertEquals("0", viewportBounds(documentWithDimensions(treatment))[boundsIndex],
                "An indefinite page no longer collapses to zero; check implementation support");
    }

    private static String[] viewportBounds(String fo) throws Exception {
        var xml = parse(areaTree(fo, IMAGE_BASE).bytes());
        return ((Element) xml.getElementsByTagName("pageViewport").item(0)).getAttribute("bounds").split(" ");
    }

    @TestFactory
    Stream<DynamicTest> supportedBehavior() {
        return Stream.of(
                DynamicTest.dynamicTest("fo:bidi-override reverses the displayed Latin glyphs with rtl", () -> {
                    String body = "<fo:bidi-override direction=\"rtl\" unicode-bidi=\"%s\">ABC</fo:bidi-override>";
                    String normal = document(block("", body.formatted("normal")));
                    String reversed = document(block("", body.formatted("bidi-override")));
                    assertTrue(glyph(normal, "A").getX() < glyph(normal, "C").getX());
                    assertTrue(glyph(reversed, "A").getX() > glyph(reversed, "C").getX());
                }),
                DynamicTest.dynamicTest("tb-rl and tb-lr advance text down the page", () -> {
                    for (String mode : List.of("tb-rl", "tb-lr")) {
                        String attribute = "writing-mode=\"" + mode + "\"";
                        String fo = document("", "page-width=\"180pt\" page-height=\"140pt\" " + attribute,
                                "<fo:region-body/>", "", attribute, block("", "ABC"));
                        validate(schema("fo.xsd"), fo);
                        var first = glyph(fo, "A");
                        var second = glyph(fo, "B");
                        assertEquals(first.getX(), second.getX(), 0.01, mode);
                        assertTrue(second.getY() > first.getY(), mode);
                    }
                }),
                DynamicTest.dynamicTest("fox:auto-toggle selects a case that fits", () -> {
                    String body = "<fo:multi-switch fox:auto-toggle=\"select-first-fitting\">"
                            + "<fo:multi-case>" + block("line-height=\"45pt\"", "Too tall") + "</fo:multi-case>"
                            + "<fo:multi-case>" + block("", "Fits") + "</fo:multi-case></fo:multi-switch>";
                    String fo = document("xmlns:fox=\"" + ExtensionElementMapping.URI + "\"",
                            "page-width=\"180pt\" page-height=\"55pt\"", "<fo:region-body/>", "", "", body);
                    validate(schema("fo.xsd"), fo);
                    assertThrows(org.xml.sax.SAXException.class,
                            () -> validate(schema("fo.xsd"), fo.replace("select-first-fitting", "true")));
                    var result = render(fo, null, IMAGE_BASE);
                    result.assertNoProblems();
                    try (var pdf = Loader.loadPDF(result.bytes())) {
                        String text = new PDFTextStripper().getText(pdf);
                        assertTrue(text.contains("Fits"), text);
                        assertFalse(text.contains("Too tall"), text);
                    }
                }),
                DynamicTest.dynamicTest("space-start and space-end add inline spacing", () -> {
                    var start = glyph(document(inline("space-start=\"14pt\"")), "I").getXDirAdj();
                    var end = glyph(document(inline("space-end=\"14pt\"")), "a", true).getXDirAdj();
                    assertEquals(14, start - glyph(document(inline("")), "I").getXDirAdj(), 0.01);
                    assertEquals(14, end - glyph(document(inline("")), "a", true).getXDirAdj(), 0.01);
                }),
                DynamicTest.dynamicTest("vertical-align shifts by a percentage of line-height", () -> {
                    String shifted = document(inline("vertical-align=\"100%\" line-height=\"12pt\""));
                    assertEquals(12, glyph(shifted, "B").getYDirAdj() - glyph(shifted, "I").getYDirAdj(), 0.01);
                }),
                DynamicTest.dynamicTest("visibility hides text without moving the next block", () -> {
                    String control = document(TEXT + block("", "After"));
                    String hidden = document(block("visibility=\"hidden\"", SAMPLE) + block("", "After"));
                    try (var pdf = Loader.loadPDF(render(hidden, null, IMAGE_BASE).bytes())) {
                        assertFalse(new PDFTextStripper().getText(pdf).contains(SAMPLE));
                    }
                    assertEquals(glyph(control, "A").getYDirAdj(), glyph(hidden, "A").getYDirAdj(), 0.01);
                }),
                DynamicTest.dynamicTest("empty-cells hide removes a separate border", () -> {
                    String table = "<fo:table table-layout=\"fixed\" width=\"100pt\" border-collapse=\"separate\">"
                            + "<fo:table-column column-width=\"100pt\"/><fo:table-body><fo:table-row>"
                            + "<fo:table-cell border=\"2pt solid red\" empty-cells=\"%s\"><fo:block/></fo:table-cell>"
                            + "</fo:table-row></fo:table-body></fo:table>";
                    assertTrue(hasRedPixels(document(table.formatted("show"))), "Control border did not paint");
                    assertFalse(hasRedPixels(document(table.formatted("hide"))), "Empty-cell border still paints");
                }));
    }

    private static boolean hasRedPixels(String fo) throws Exception {
        try (var pdf = Loader.loadPDF(render(fo, null, IMAGE_BASE).bytes())) {
            var image = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) & 0xffffff) == 0xff0000) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static TextPosition glyph(String fo, String character) throws Exception {
        return glyph(fo, character, false);
    }

    private static TextPosition glyph(String fo, String character, boolean last) throws Exception {
        var found = new ArrayList<TextPosition>();
        try (var pdf = Loader.loadPDF(render(fo, null, IMAGE_BASE).bytes())) {
            new PDFTextStripper() {
                @Override
                protected void processTextPosition(TextPosition text) {
                    if (character.equals(text.getUnicode())) {
                        found.add(text);
                    }
                    super.processTextPosition(text);
                }
            }.getText(pdf);
        }
        assertFalse(found.isEmpty(), "Missing PDF glyph " + character);
        return last ? found.getLast() : found.getFirst();
    }

    private static void assertCaptionStillBeforeTable() throws Exception {
        String fo = example("print-table/document.fo")
                .replace("<fo:table-and-caption>", "<fo:table-and-caption caption-side=\"after\">");
        assertTrue(glyph(fo, "S").getY() < glyph(fo, "N").getY(),
                "Caption no longer sits above the table with caption-side=after");
    }

    private static void assertTableFallback(String feature) throws Exception {
        String original = tableWithoutCaption();
        String fo = feature.equals(AUTO_TABLE)
                ? original.replace("table-layout=\"fixed\"", feature)
                : original.replace("<fo:table table-layout", "<fo:table " + feature + " table-layout");
        validate(schema("fo.xsd"), fo);
        var result = render(fo, null, IMAGE_BASE);
        assertEvent(result, ".unimplementedFeature", feature);
        try (var pdf = Loader.loadPDF(result.bytes())) {
            assertEquals(1, pdf.getNumberOfPages(), "FOP falls back rather than failing to render");
        }
    }

    private static String tableWithoutCaption() throws Exception {
        // Keep table-property probes independent of the wrapper, which first rendered in 2.12.
        return example("print-table/document.fo")
                .replaceFirst("(?s)<fo:table-and-caption>.*?</fo:table-caption>", "")
                .replace("</fo:table-and-caption>", "");
    }

    private static void assertEvent(Fixtures.Rendered result, String eventSuffix, String parameter) {
        assertTrue(result.events().stream().anyMatch(event -> event.getEventID().endsWith(eventSuffix)
                        && (parameter == null || event.getParams().values().stream()
                        .map(String::valueOf).anyMatch(value -> value.contains(parameter)))),
                () -> "FOP no longer emitted " + eventSuffix + " for " + parameter + "; check support. Events: "
                        + result.events().stream().map(event -> event.getEventID() + " " + event.getParams()).toList());
    }

    private static void compareRepeatingBackground(String repeat, String property) throws Exception {
        String background = "background-image=\"url('appendix.svg')\" background-repeat=\"" + repeat + "\"";
        assertSameLayout(document(block(background, SAMPLE)), document(block(background + " " + property, SAMPLE)));
    }

    private static void compareCharacter(String attribute, String value) throws Exception {
        String element = "Before<fo:character character=\"" + value + "\" %s/>after";
        assertSameLayout(document(block("", element.formatted(""))), document(block("", element.formatted(attribute))));
    }

    private static String inline(String attributes) {
        return block("", "Before <fo:inline " + attributes + ">Inside</fo:inline> after");
    }

    private static String graphic(String attributes) {
        return block("", "<fo:external-graphic src=\"url('appendix.svg')\" content-width=\"20pt\" " + attributes + "/>");
    }

    private static String leader(String attributes) {
        return block("", "Before<fo:leader leader-pattern=\"dots\" leader-pattern-width=\"10pt\" leader-length=\"40pt\" "
                + attributes + "/>after");
    }

    private static String block(String attributes, String content) {
        return "<fo:block " + attributes + ">" + content + "</fo:block>";
    }

    private static String documentWithDimensions(String dimensions) {
        return document("", dimensions, "<fo:region-body/>", "", "", TEXT);
    }

    private static String onPageTwo(String content) {
        return document("", "page-width=\"180pt\" page-height=\"140pt\"", "<fo:region-body/>",
                "", "initial-page-number=\"2\"", content);
    }

    private static String narrow(String content) {
        return document("", "page-width=\"100pt\" page-height=\"140pt\"", "<fo:region-body/>", "", "",
                block("font-family=\"Courier\" font-size=\"10pt\"", content));
    }

    private static String floatAndText(String side, String attribute) {
        return block("", floatingBox(side, "", "Float"))
                + block(attribute, "Text around a float. Text around a float. Text around a float.");
    }

    private static String floatingBox(String side, String attribute, String text) {
        return "<fo:float float=\"" + side + "\" " + attribute + ">"
                + "<fo:block-container width=\"36pt\" height=\"32pt\">" + block("", text)
                + "</fo:block-container></fo:float>";
    }

    private static String document(String content) {
        return document("", "page-width=\"180pt\" page-height=\"140pt\"", "<fo:region-body/>", "", "", content);
    }

    private static String document(String root, String dimensions, String regions, String declarations,
                                   String sequence, String content) {
        return """
                <fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format" %s>
                  <fo:layout-master-set>
                    <fo:simple-page-master master-name="probe" margin="10pt" %s>%s</fo:simple-page-master>
                  </fo:layout-master-set>
                  %s
                  <fo:page-sequence master-reference="probe" force-page-count="no-force" %s>
                    <fo:flow flow-name="xsl-region-body">%s</fo:flow>
                  </fo:page-sequence>
                </fo:root>
                """.formatted(root, dimensions, regions, declarations, sequence, content);
    }

    private static void assertSameLayout(String control, String treatment) throws Exception {
        assertSameLayout(control, treatment, IMAGE_BASE, false);
    }

    private static void assertSameLayout(String control, String treatment, Path base, boolean requireNoProblems)
            throws Exception {
        assertNotEquals(control, treatment, "Probe must change the source");
        var before = areaTree(control, base);
        var after = areaTree(treatment, base);
        if (requireNoProblems) {
            before.assertNoProblems();
            after.assertNoProblems();
        }
        assertEquals(new String(before.bytes(), StandardCharsets.UTF_8),
                new String(after.bytes(), StandardCharsets.UTF_8), "FOP changed the laid-out areas; recheck the limitation");
    }

    private static void add(Map<String, Executable> probes, String feature, Executable probe) {
        if (probes.put(feature, probe) != null) {
            throw new IllegalStateException("Duplicate feature probe: " + feature);
        }
    }
}
