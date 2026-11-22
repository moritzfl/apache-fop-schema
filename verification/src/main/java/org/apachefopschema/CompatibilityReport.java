package org.apachefopschema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/** Generates stable, standalone reports from the compatibility annotations in the XSD files. */
public final class CompatibilityReport {
    private static final String NS = "urn:apache-fop-schema:compatibility";
    private static final Set<String> ATTRIBUTES = Set.of("feature", "status", "fop", "evidence");
    private static final Set<String> EVIDENCE = Set.of("pdf-test", "area-tree-test", "runtime-probe",
            "source", "compliance", "upstream-docs", "schema-scope");
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "unsupported", "Unsupported",
            "partial", "Partial",
            "plug-in-required", "Plug-in required",
            "not-modeled", "Not modeled");
    private static final List<String> STATUS_ORDER = List.of("unsupported", "partial", "plug-in-required", "not-modeled");

    private CompatibilityReport() {
    }

    /** Prints the report for the schema directory passed on the command line. */
    public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: CompatibilityReport <schema directory> [markdown|html|text]");
        }
        var gaps = read(Path.of(args[0]));
        System.out.print(switch (args.length == 1 ? "markdown" : args[1]) {
            case "markdown", "md" -> markdown(gaps);
            case "html" -> html(gaps);
            case "text" -> text(gaps);
            default -> throw new IllegalArgumentException("Unknown report format: " + args[1]);
        });
    }

    /** Reads one record per annotated feature; rejects malformed and duplicate records. */
    static List<Gap> read(Path schemaDir) throws IOException, ParserConfigurationException, SAXException {
        var factory = DocumentBuilderFactory.newDefaultNSInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var parser = factory.newDocumentBuilder();
        var byFeature = new TreeMap<String, Gap>();
        try (var files = Files.list(schemaDir)) {
            var schemas = files.filter(path -> path.getFileName().toString().endsWith(".xsd")).sorted().toList();
            for (Path file : schemas) {
                var document = parser.parse(file.toFile());
                var notes = document.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "documentation");
                for (int i = 0; i < notes.getLength(); i++) {
                    var gap = readNote(file, (Element) notes.item(i));
                    if (gap != null && byFeature.putIfAbsent(gap.feature(), gap) != null) {
                        throw malformed(file, gap.feature(), "duplicate feature");
                    }
                }
            }
        }
        if (byFeature.isEmpty()) {
            throw new IllegalArgumentException("No compatibility annotations in " + schemaDir);
        }
        return List.copyOf(byFeature.values());
    }

    private static Gap readNote(Path file, Element doc) {
        var attrs = doc.getAttributes();
        int metadata = 0;
        for (int i = 0; i < attrs.getLength(); i++) {
            var attr = attrs.item(i);
            if (NS.equals(attr.getNamespaceURI())) {
                if (!ATTRIBUTES.contains(attr.getLocalName())) {
                    throw malformed(file, doc.getAttributeNS(NS, "feature"), "unknown attribute " + attr.getLocalName());
                }
                metadata++;
            }
        }
        if (metadata == 0) {
            return null;
        }
        String feature = doc.getAttributeNS(NS, "feature").strip();
        String status = doc.getAttributeNS(NS, "status");
        String fop = doc.getAttributeNS(NS, "fop");
        var evidence = List.of(doc.getAttributeNS(NS, "evidence").strip().split("\\s+"));
        String label = STATUS_LABELS.get(status);
        if (feature.isEmpty() || label == null || !fop.matches("\\d+\\.\\d+(\\.\\d+)?")
                || !EVIDENCE.containsAll(evidence)) {
            throw malformed(file, feature, "expected feature, known status, release version and evidence");
        }
        String[] paragraphs = doc.getTextContent().strip().split("\\R[ \\t]*\\R");
        String last = paragraphs[paragraphs.length - 1].replaceAll("\\s+", " ").strip();
        String prefix = "FOP " + fop + ": " + label + ".";
        if (!last.equals(prefix) && !last.startsWith(prefix + " ")) {
            throw malformed(file, feature, "final documentation paragraph must start with '" + prefix + "'");
        }
        return new Gap(feature, status, fop, evidence, last.substring(prefix.length()).strip(),
                file.getFileName().toString());
    }

    static String markdown(List<Gap> gaps) {
        var out = new StringBuilder("""
                # Apache FOP compatibility gaps

                Generated from the XSD documentation. Not a full conformance table.
                """);
        for (String status : STATUS_ORDER) {
            var section = gaps.stream().filter(gap -> gap.status().equals(status)).toList();
            if (section.isEmpty()) {
                continue;
            }
            out.append("\n## ").append(STATUS_LABELS.get(status)).append(" (").append(section.size()).append(")\n\n");
            for (Gap gap : section) {
                out.append("- ").append(code(gap.feature())).append(" — FOP ").append(gap.fop())
                        .append("; ").append(String.join(", ", gap.evidence()))
                        .append("; ").append(code("schema/" + gap.schema())).append('\n');
                if (!gap.detail().isEmpty()) {
                    out.append("\n  ").append(markdownText(gap.detail())).append("\n\n");
                }
            }
        }
        return out.toString();
    }

    static String html(List<Gap> gaps) {
        var document = Parser.builder().build().parse(markdown(gaps));
        String content = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(document);
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Apache FOP compatibility gaps</title>
                  <style>
                    :root { color-scheme: light dark; }
                    body { font: 1rem/1.55 system-ui, sans-serif; margin: 0; }
                    main { max-width: 70rem; margin: auto; padding: 1.5rem; overflow-wrap: anywhere; }
                    h1, h2 { line-height: 1.2; }
                    h2 { margin-top: 2em; }
                    li { margin-bottom: .75em; }
                    li p { margin: .35em 0; }
                    code { font-size: .95em; }
                  </style>
                </head>
                <body>
                <main>
                """ + content + "</main>\n</body>\n</html>\n";
    }

    static String text(List<Gap> gaps) {
        var out = new StringBuilder("Apache FOP compatibility gaps\n");
        for (String status : STATUS_ORDER) {
            var section = gaps.stream().filter(gap -> gap.status().equals(status)).toList();
            if (section.isEmpty()) {
                continue;
            }
            String heading = STATUS_LABELS.get(status) + " (" + section.size() + ")";
            out.append('\n').append(heading).append('\n').append("-".repeat(heading.length())).append('\n');
            for (Gap gap : section) {
                out.append("  ").append(gap.feature()).append('\n');
                appendWrapped(out, "FOP " + gap.fop() + " | " + String.join(", ", gap.evidence())
                        + " | schema/" + gap.schema());
                appendWrapped(out, gap.detail());
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void appendWrapped(StringBuilder out, String text) {
        if (text.isEmpty()) {
            return;
        }
        out.append("    ");
        int column = 4;
        for (String word : text.split(" ")) {
            if (column > 4 && column + 1 + word.length() > 88) {
                out.append("\n    ");
                column = 4;
            }
            if (column > 4) {
                out.append(' ');
                column++;
            }
            out.append(word);
            column += word.length();
        }
        out.append('\n');
    }

    private static String code(String text) {
        int run = 0;
        int longest = 0;
        for (int i = 0; i < text.length(); i++) {
            run = text.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        String delimiter = "`".repeat(longest + 1);
        return longest == 0 ? delimiter + text + delimiter : delimiter + " " + text + " " + delimiter;
    }

    private static String markdownText(String text) {
        // Treat annotation details as literal prose, not renderer-specific Markdown or raw HTML.
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replaceAll("([\\\\`*_~\\[\\]])", "\\\\$1")
                .replaceFirst("^([#+-])", "\\\\$1")
                .replaceFirst("^(\\d+)([.)])", "$1\\\\$2");
    }

    private static IllegalArgumentException malformed(Path file, String feature, String reason) {
        return new IllegalArgumentException(file + " (" + feature + "): " + reason);
    }

    record Gap(String feature, String status, String fop, List<String> evidence, String detail, String schema) {
    }
}
