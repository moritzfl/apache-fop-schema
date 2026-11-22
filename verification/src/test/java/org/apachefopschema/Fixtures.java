package org.apachefopschema;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.apache.fop.apps.FOUserAgent;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopConfParser;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.FopFactoryBuilder;
import org.apache.fop.apps.MimeConstants;
import org.apache.fop.events.Event;
import org.apache.fop.events.model.EventSeverity;
import org.w3c.dom.Document;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared inputs and XML/FOP plumbing for the verification tests. */
final class Fixtures {
    static final Path SCHEMAS = Path.of(System.getProperty("schemaDir"));
    static final Path EXAMPLES = Path.of(System.getProperty("exampleDir"));
    private static final Path PDFS = Path.of(System.getProperty("pdfDir"));

    private Fixtures() {
    }

    static Schema schema(String name) throws Exception {
        SchemaFactory factory = SchemaFactory.newDefaultInstance();
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        return factory.newSchema(SCHEMAS.resolve(name).toFile());
    }

    static void validate(Schema schema, String xml) throws Exception {
        var validator = schema.newValidator();
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultNSInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    static String example(String name) throws Exception {
        return Files.readString(EXAMPLES.resolve(name));
    }

    static String transform(String stylesheet, String xml) throws Exception {
        TransformerFactory factory = TransformerFactory.newDefaultInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var file = Path.of(System.getProperty("stylesheetDir"), stylesheet).toFile();
        var transformer = factory.newTransformer(new StreamSource(file));
        var output = new StringWriter();
        transformer.transform(new StreamSource(new StringReader(xml)), new StreamResult(output));
        return output.toString();
    }

    static Rendered render(String fo, String config, Path base) throws Exception {
        return render(fo, config, base, true);
    }

    static Rendered render(String fo, String config, Path base, boolean ignoreSchemaHints) throws Exception {
        return format(fo, config, base, ignoreSchemaHints, MimeConstants.MIME_PDF);
    }

    static Rendered areaTree(String fo, Path base) throws Exception {
        return format(fo, null, base, true, MimeConstants.MIME_FOP_AREA_TREE);
    }

    private static Rendered format(String fo, String config, Path base, boolean ignoreSchemaHints, String mime)
            throws Exception {
        FopFactoryBuilder builder = config == null ? new FopFactoryBuilder(base.toUri())
                : new FopConfParser(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)), base.toUri())
                    .getFopFactoryBuilder();
        // Schema hints support XML tooling, not FO formatting. Keep strict validation for other namespaces.
        if (ignoreSchemaHints) {
            builder.ignoreNamespace(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
        }
        FopFactory factory = builder.build();
        FOUserAgent agent = factory.newFOUserAgent();
        List<Event> events = new ArrayList<>();
        agent.getEventBroadcaster().addEventListener(events::add);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Fop fop = factory.newFop(mime, agent, output);
        StreamSource source = new StreamSource(new StringReader(fo), base.resolve("document.fo").toUri().toString());
        TransformerFactory.newDefaultInstance().newTransformer().transform(source, new SAXResult(fop.getDefaultHandler()));
        return new Rendered(output.toByteArray(), List.copyOf(events));
    }

    static byte[] renderExample(String name, String foPath, String configPath) throws Exception {
        String fo = example(foPath);
        String config = configPath == null ? null : example(configPath);
        validate(schema("fo.xsd"), fo);
        if (config != null) {
            validate(schema("fop-config-no-namespace.xsd"), config);
        }
        var pdf = render(fo, config, EXAMPLES.resolve(foPath).getParent());
        pdf.assertNoProblems();
        writePdf(name, pdf.bytes());
        return pdf.bytes();
    }

    static void writePdf(String name, byte[] bytes) throws Exception {
        Files.createDirectories(PDFS);
        Files.write(PDFS.resolve(name + ".pdf"), bytes);
    }

    record Rendered(byte[] bytes, List<Event> events) {
        void assertNoProblems() {
            var problems = events.stream().filter(event -> event.getSeverity() != EventSeverity.INFO).toList();
            assertTrue(problems.isEmpty(), () -> "Unexpected FOP events: " + problems.stream()
                    .map(event -> event.getEventID() + " " + event.getParams()).toList());
        }
    }
}
