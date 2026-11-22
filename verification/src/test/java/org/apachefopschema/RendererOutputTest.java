package org.apachefopschema;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

import org.afplib.afplib.BPG;
import org.afplib.afplib.EPG;
import org.afplib.afplib.PGD;
import org.afplib.base.SF;
import org.afplib.io.AfpInputStream;
import org.apache.fop.afp.AFPEventProducer;
import org.apache.fop.apps.MimeConstants;
import org.apache.xmlgraphics.ps.dsc.DSCParser;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentLanguageLevel;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentPage;
import org.apache.xmlgraphics.ps.dsc.events.DSCCommentPageBoundingBox;
import org.junit.jupiter.api.Test;

import static org.apachefopschema.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks renderer-specific settings in the actual AFP, PostScript and PNG output. */
class RendererOutputTest {
    private static final String FO = "renderer-output/document.fo";
    private static final String CONFIG = "renderer-output/fop.xconf";
    // FOP reports default font setup even though this fixture contains no text.
    private static final String AFP_DEFAULT_FONT_WARNING = AFPEventProducer.class.getName() + ".warnDefaultFontSetup";

    @Test
    void afpResolutionChangesPageDescriptorUnitsAndSize() throws Exception {
        assertAfpPage(renderExample("renderer-output.afp", FO, CONFIG, MimeConstants.MIME_AFP, AFP_DEFAULT_FONT_WARNING), 300);
        assertAfpPage(renderWithDefaults(MimeConstants.MIME_AFP, AFP_DEFAULT_FONT_WARNING), 240);
    }

    @Test
    void postScriptLanguageLevelChangesWithoutChangingPageBounds() throws Exception {
        assertPostScriptPage(renderExample("renderer-output.ps", FO, CONFIG, MimeConstants.MIME_POSTSCRIPT), 2);
        assertPostScriptPage(renderWithDefaults(MimeConstants.MIME_POSTSCRIPT), 3);
    }

    @Test
    void pngResolutionAndTransparencyChangePixels() throws Exception {
        assertBitmap(renderExample("renderer-output.png", FO, CONFIG, MimeConstants.MIME_PNG), 288, 144, 0);
        assertBitmap(renderWithDefaults(MimeConstants.MIME_PNG), 144, 72, 255);
    }

    private static byte[] renderWithDefaults(String mime, String... allowedWarningIds) throws Exception {
        var output = render(example(FO), null, EXAMPLES.resolve(FO).getParent(), mime);
        output.assertNoProblems(allowedWarningIds);
        return output.bytes();
    }

    private static void assertAfpPage(byte[] bytes, int dpi) throws Exception {
        int begins = 0;
        int ends = 0;
        PGD descriptor = null;
        try (var input = new AfpInputStream(new ByteArrayInputStream(bytes))) {
            for (SF field; (field = input.readStructuredField()) != null; ) {
                if (field instanceof BPG) {
                    begins++;
                } else if (field instanceof EPG) {
                    ends++;
                } else if (field instanceof PGD page) {
                    assertNull(descriptor, "One page descriptor expected");
                    descriptor = page;
                }
            }
        }
        assertEquals(1, begins, "Begin Page fields");
        assertEquals(1, ends, "End Page fields");
        assertNotNull(descriptor, "Missing Page Descriptor");
        // PGD expresses inch-based resolution in units per ten inches.
        assertEquals(dpi * 10, descriptor.getXpgUnits(), "Horizontal measurement units");
        assertEquals(dpi * 10, descriptor.getYpgUnits(), "Vertical measurement units");
        assertEquals(2 * dpi, descriptor.getXpgSize(), "2in page width");
        assertEquals(dpi, descriptor.getYpgSize(), "1in page height");
    }

    private static void assertPostScriptPage(byte[] bytes, int level) throws Exception {
        var parser = new DSCParser(new ByteArrayInputStream(bytes));
        int actualLevel = 0;
        int pages = 0;
        DSCCommentPageBoundingBox bounds = null;
        while (parser.hasNext()) {
            var event = parser.nextEvent();
            if (event instanceof DSCCommentLanguageLevel languageLevel) {
                actualLevel = languageLevel.getLanguageLevel();
            } else if (event instanceof DSCCommentPage) {
                pages++;
            } else if (event instanceof DSCCommentPageBoundingBox pageBounds) {
                bounds = pageBounds;
            }
        }
        assertEquals(level, actualLevel, "DSC language level");
        assertEquals(1, pages, "DSC page count");
        assertNotNull(bounds, "Missing DSC page bounding box");
        assertEquals(144, bounds.getBoundingBox().getWidth(), "2in page width in points");
        assertEquals(72, bounds.getBoundingBox().getHeight(), "1in page height in points");
    }

    private static void assertBitmap(byte[] bytes, int width, int height, int cornerAlpha) throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image, "FOP output is not a readable image");
        assertEquals(width, image.getWidth(), "Pixel width");
        assertEquals(height, image.getHeight(), "Pixel height");
        assertEquals(cornerAlpha, image.getRGB(0, 0) >>> 24, "Alpha outside the painted region");
        assertEquals(0xffef1122, image.getRGB(width / 8, height / 4), "Opaque fill inside the rectangle");
    }
}
