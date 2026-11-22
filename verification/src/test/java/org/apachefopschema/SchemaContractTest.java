package org.apachefopschema;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import static org.apachefopschema.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Enforces portable XSD declarations, offline resolution, and useful editor documentation. */
class SchemaContractTest {
    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final Set<String> DOCUMENTED = Set.of("element", "attribute", "simpleType", "complexType",
            "group", "attributeGroup", "schema");

    @Test
    void allSchemasCompileOnTheirOwnAndUseLocalRelocatableImports(@TempDir Path relocated) throws Exception {
        List<Path> schemas;
        try (var files = Files.list(SCHEMAS)) {
            schemas = files.filter(file -> file.getFileName().toString().endsWith(".xsd")).sorted().toList();
        }
        assertFalse(schemas.isEmpty());
        for (Path file : schemas) {
            Files.copy(file, relocated.resolve(file.getFileName()));
        }
        for (Path file : schemas) {
            Element root = parse(Files.readAllBytes(file)).getDocumentElement();
            assertEquals("schema", root.getLocalName(), file.toString());
            assertEquals(XSD, root.getNamespaceURI(), file.toString());
            assertTrue(Files.readString(file, StandardCharsets.UTF_8).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
                    file::toString);
            assertDocumentation(file, root);
            var nodes = root.getElementsByTagNameNS(XSD, "*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                String kind = node.getLocalName();
                if (DOCUMENTED.contains(kind) && node.hasAttribute("name")) {
                    assertDocumentation(file, node);
                }
                assertFalse(node.hasAttribute("substitutionGroup"), "Use explicit child references for discovery: " + file);
                if ("import".equals(kind) || "include".equals(kind)) {
                    String location = node.getAttribute("schemaLocation");
                    assertTrue(location.matches("[a-z][a-z0-9-]*\\.xsd"), () -> file + ": nonlocal import " + location);
                    Path target = relocated.resolve(location);
                    assertTrue(Files.isRegularFile(target), target::toString);
                    Element targetRoot = parse(Files.readAllBytes(target)).getDocumentElement();
                    if ("import".equals(kind)) {
                        assertEquals(node.getAttribute("namespace"), targetRoot.getAttribute("targetNamespace"), file + ": " + location);
                    }
                }
            }
            var factory = javax.xml.validation.SchemaFactory.newDefaultInstance();
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            assertDoesNotThrow(() -> factory.newSchema(relocated.resolve(file.getFileName()).toFile()), file::toString);
        }
    }

    @Test
    void exampleSchemaHintsResolveWithoutCatalogOrExplicitSchema() throws Exception {
        var factory = javax.xml.validation.SchemaFactory.newDefaultInstance();
        try (var files = Files.walk(EXAMPLES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".fo") || path.toString().endsWith(".xconf")).toList()) {
                var root = parse(Files.readAllBytes(file)).getDocumentElement();
                String hint = root.hasAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "schemaLocation")
                        ? root.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "schemaLocation").split("\\s+")[1]
                        : root.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "noNamespaceSchemaLocation");
                assertFalse(URI.create(hint).isAbsolute(), file::toString);
                assertTrue(Files.isRegularFile(file.getParent().resolve(hint)), file::toString);
                var validator = factory.newSchema().newValidator();
                validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
                assertDoesNotThrow(() -> validator.validate(new javax.xml.transform.stream.StreamSource(file.toFile())), file::toString);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"flow-name", "master-reference", "master-name", "ref-id", "internal-destination"})
    void missingRequiredAttributesAreRejected(String attribute) throws Exception {
        String fo = example("navigation/document.fo");
        String invalid = "internal-destination".equals(attribute)
                ? fo.replace("<fox:destination internal-destination=\"results\"", "<fox:destination")
                : fo.replaceFirst("(?<=\\s)" + attribute + "=\"[^\"]+\"", "");
        assertNotEquals(fo, invalid);
        assertThrows(SAXException.class, () -> validate(schema("fo.xsd"), invalid));
    }

    @Test
    void unknownNamesWrongNamespacesAndValuesAreNotAccepted() throws Exception {
        var fo = schema("fo.xsd");
        String navigation = example("navigation/document.fo");
        assertThrows(SAXException.class, () -> validate(fo, navigation.replace("fo:bookmark-title", "fo:bookmark-lable")));
        assertThrows(SAXException.class, () -> validate(fo, navigation.replace("font-size", "font-szie")));
        assertThrows(SAXException.class, () -> validate(fo, navigation.replace("font-size=\"18pt\"", "fox:alt-text=\"not a graphic\"")));
        assertThrows(SAXException.class, () -> validate(fo, navigation.replace("id=\"results\"", "id=\"report-pages\"")));
        assertThrows(SAXException.class, () -> validate(fo, navigation.replace("<fox:destination", "<fo:destination")));
        assertThrows(SAXException.class, () -> validate(fo, navigation.replace("<fox:destination", "<fox:form-field")));
        String table = example("print-table/document.fo");
        assertThrows(SAXException.class, () -> validate(fo, table.replace("trim-box", "invalid-box")));
        assertThrows(SAXException.class, () -> validate(fo, table.replace("table-layout=\"fixed\"", "table-layout=\"fictional\"")));
        String tagged = example("tagged-metadata/document.fo");
        assertThrows(SAXException.class, () -> validate(fo, tagged.replace("rdf:about=", "about=")));
        assertThrows(SAXException.class, () -> validate(fo, tagged.replace("rdf:Alt", "rdf:Bag")));
        assertThrows(SAXException.class, () -> validate(fo, tagged.replace("fox:alt-text", "alt-text")));
        var config = schema("fop-config-no-namespace.xsd");
        assertThrows(SAXException.class, () -> validate(config, "<fop version='1.0'><accessibility>1</accessibility></fop>"));
        assertThrows(SAXException.class, () -> validate(config, "<fop version='1.0'><accessibility> true </accessibility></fop>"));
        assertThrows(SAXException.class, () -> validate(config, "<fop version='1.0'><accessiblity>true</accessiblity></fop>"));
    }

    private static void assertDocumentation(Path file, Element declaration) {
        String name = declaration.getAttribute("name");
        assertTrue(hasDocumentation(declaration), () -> file.getFileName() + ": undocumented "
                + declaration.getLocalName() + " " + name);
    }

    private static boolean hasDocumentation(Element declaration) {
        if (declaration.getFirstChild() == null) {
            return false;
        }
        for (var child = declaration.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element annotation && XSD.equals(annotation.getNamespaceURI())
                    && "annotation".equals(annotation.getLocalName())) {
                var docs = annotation.getElementsByTagNameNS(XSD, "documentation");
                for (int i = 0; i < docs.getLength(); i++) {
                    if (!docs.item(i).getTextContent().isBlank()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
