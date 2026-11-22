package org.apachefopschema;

import java.util.List;

import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.ListItem;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies literal content and standard Markdown structure in the public report formats. */
class CompatibilityFormatsTest {
    private static final List<CompatibilityReport.Gap> SAMPLE = List.of(
            new CompatibilityReport.Gap("fo:panel `example`", "unsupported", "2.12",
                    List.of("pdf-test"), "<fop> & [literal] | *not italic*", "fo.xsd"),
            new CompatibilityReport.Gap("nowrap", "partial", "2.12",
                    List.of("source"), "", "fo.xsd"));

    @Test
    void plainCommonMarkParsesHeadingListAndCodeWithoutExtensions() {
        var doc = Parser.builder().build().parse(CompatibilityReport.markdown(SAMPLE));
        var h1 = assertInstanceOf(Heading.class, doc.getFirstChild());
        assertEquals(1, h1.getLevel());
        var intro = assertInstanceOf(Paragraph.class, h1.getNext());
        var h2 = assertInstanceOf(Heading.class, intro.getNext());
        assertEquals(2, h2.getLevel());
        var list = assertInstanceOf(BulletList.class, h2.getNext());
        var item = assertInstanceOf(ListItem.class, list.getFirstChild());
        var entry = assertInstanceOf(Paragraph.class, item.getFirstChild());
        assertEquals("fo:panel `example`", assertInstanceOf(Code.class, entry.getFirstChild()).getLiteral());
        assertInstanceOf(Paragraph.class, entry.getNext(), "Details belong to the same bullet");
    }

    @Test
    void htmlIsStandaloneAndEscapesAnnotationText() {
        String html = CompatibilityReport.html(SAMPLE);
        assertTrue(html.startsWith("<!doctype html>\n"));
        assertTrue(html.contains("<html lang=\"en\">"));
        assertTrue(html.contains("<meta charset=\"utf-8\">"));
        assertTrue(html.contains("<h2>Unsupported (1)</h2>"));
        assertTrue(html.contains("<code>fo:panel `example`</code>"));
        assertTrue(html.contains("&lt;fop&gt; &amp; [literal] | *not italic*"), html);
        assertFalse(html.contains("<fop>"));
        assertFalse(html.contains("<em>"));
        assertFalse(html.contains("<script"));
        assertTrue(html.endsWith("</html>\n"));
    }

    @Test
    void terminalOutputGroupsAndWrapsNotes() {
        String detail = "A well-spaced detail. ".repeat(8).strip();
        var gap = new CompatibilityReport.Gap("nowrap", "partial", "2.12", List.of("source"), detail, "fo.xsd");
        String text = CompatibilityReport.text(List.of(gap));
        assertTrue(text.contains("Partial (1)\n-----------\n  nowrap\n    FOP 2.12 | source | schema/fo.xsd\n"));
        var wrapped = text.lines().skip(6).filter(line -> !line.isBlank()).map(String::strip).toList();
        assertTrue(wrapped.size() > 1);
        assertEquals(detail, String.join(" ", wrapped));
        assertTrue(text.lines().allMatch(line -> line.length() <= 88), text);
    }
}
