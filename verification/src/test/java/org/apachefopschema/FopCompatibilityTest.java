package org.apachefopschema;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.apachefopschema.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Validates the XSD compatibility annotations and their pinned review baseline. */
class FopCompatibilityTest {
    @Test
    void compatibilityInventoryMatchesBaselineVersion() throws Exception {
        String baseline = System.getProperty("fopBaseline");
        var gaps = CompatibilityReport.read(SCHEMAS);
        for (var gap : gaps) {
            assertEquals(baseline, gap.fop(), () -> "Recheck " + gap.feature() + " against FOP " + baseline);
            if (!gap.status().equals("not-modeled")) {
                assertTrue(gap.evidence().contains("source"), () -> "Missing source review for " + gap.feature());
                assertTrue(gap.evidence().stream().anyMatch(evidence -> evidence.endsWith("-test") || evidence.equals("runtime-probe")),
                        () -> "Missing probe evidence for " + gap.feature());
            }
        }
    }

    @Test
    void reportRejectsDuplicateFeaturesAndOutdatedProse(@TempDir Path schemas) throws Exception {
        String schema = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:c="urn:apache-fop-schema:compatibility">
                    <xs:annotation>
                        <xs:documentation c:feature="example" c:status="partial" c:fop="2.12" c:evidence="source">
                            FOP 2.12: Partial. A test note.
                        </xs:documentation>
                    </xs:annotation>
                </xs:schema>
                """;
        Files.writeString(schemas.resolve("one.xsd"), schema);
        var gap = CompatibilityReport.read(schemas).getFirst();
        assertEquals("example", gap.feature());
        assertEquals("A test note.", gap.detail());
        Files.writeString(schemas.resolve("two.xsd"), schema);
        var duplicate = assertThrows(IllegalArgumentException.class, () -> CompatibilityReport.read(schemas));
        assertTrue(duplicate.getMessage().contains("duplicate feature"), duplicate::getMessage);
        Files.writeString(schemas.resolve("two.xsd"), schema.replace("c:feature=\"example\"", "c:feature=\"another\"")
                .replace("FOP 2.12: Partial.", "FOP 2.11: Partial."));
        var outdated = assertThrows(IllegalArgumentException.class, () -> CompatibilityReport.read(schemas));
        assertTrue(outdated.getMessage().contains("must start with 'FOP 2.12: Partial.'"), outdated::getMessage);
    }
}
