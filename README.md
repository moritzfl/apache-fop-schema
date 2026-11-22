# Schemas for Apache FOP

Documented XML Schema 1.0 vocabularies for writing XSL-FO and configuring Apache FOP. The verification suite renders with FOP and checks the PDFs with Apache PDFBox. See the [Gradle version catalog](verification/gradle/libs.versions.toml) for pinned versions. Schema validation does not guarantee rendering or PDF/A or PDF/UA conformance.

## Choose a schema

All XSD files live in [`schema/`](schema/). Copy the directory as a unit so its relative imports resolve without network access. If you used the old root-level paths, add `schema/` before the filename in your schema hints or catalog mappings. Namespaces have not changed.

| Entry point | Use |
| --- | --- |
| [`fo.xsd`](schema/fo.xsd) | Validate XSL-FO 1.1, including FOP extensions and document metadata. |
| [`fop-config.xsd`](schema/fop-config.xsd) | Validate configuration using this project's namespace. Use it as the default namespace for direct rendering with FOP. |
| [`fop-config-no-namespace.xsd`](schema/fop-config-no-namespace.xsd) | Validate configuration using the no-namespace format documented by Apache FOP. |
| [`fox.xsd`](schema/fox.xsd), [`pdf.xsd`](schema/pdf.xsd) | General FOP extensions (`fox:`) and PDF-specific extensions (`pdf:`), imported by `fo.xsd`. |
| [`xmpmeta.xsd`](schema/xmpmeta.xsd), [`rdf.xsd`](schema/rdf.xsd), [`dc.xsd`](schema/dc.xsd), [`xml.xsd`](schema/xml.xsd) | XMP wrapper, RDF/XML and Dublin Core subsets for PDF metadata, plus `xml:lang`. Use `xmpmeta.xsd` for standalone XMP. These are not general-purpose RDF or XMP validators. |

See [examples and recipes](docs/examples.md) and [schema contribution checks](docs/schema-contract.md). Generate the current [FOP compatibility report](#generate-the-compatibility-report) from the XSD annotations.

## Configure FOP

Use a default namespace to identify the configuration vocabulary explicitly. This project defines the namespace; FOP does not require it. The pinned FOP release accepts this form because the element names remain unprefixed. Adjust the schema path relative to your `.xconf` file.

```xml
<fop version="1.0"
     xmlns="http://xmlgraphics.apache.org/fop/config"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://xmlgraphics.apache.org/fop/config schema/fop-config.xsd">
    <strict-configuration>true</strict-configuration>
    <default-page-settings width="210mm" height="297mm"/>
</fop>
```

For the no-namespace form, omit the default namespace and use `xsi:noNamespaceSchemaLocation`:

```xml
<fop version="1.0"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:noNamespaceSchemaLocation="schema/fop-config-no-namespace.xsd">
    <strict-configuration>true</strict-configuration>
    <default-page-settings width="210mm" height="297mm"/>
    <renderers>
        <renderer mime="application/pdf">
            <version>1.7</version>
        </renderer>
    </renderers>
</fop>
```

Apache FOP also provides an [upstream configuration schema](https://github.com/apache/xmlgraphics-fop/blob/HEAD/fop/src/foschema/fop-configuration.xsd). This project's XSD 1.0 schema documents more options, but uses one content model for `<renderer>`: it cannot choose different child rules based on `@mime`. That means a PDF-only option can validate under the PNG renderer. Separate validation rules could check those combinations; an output test is still needed for requirements such as font embedding or a PDF profile.

### Normalize a namespaced configuration

A default namespace and a prefix bound to the same URI are equivalent for schema validation. The pinned FOP release reads configuration tag names literally, so a prefixed file (`<f:fop>`, `<f:renderers>`) can validate yet **silently lose its settings**. A successful render alone does not prove the settings took effect.

Use [`normalize-fop-config.xsl`](tools/normalize-fop-config.xsl) when passing prefixed input to FOP or when you need no-namespace output. It accepts either form of namespaced input, rewrites only elements in this project's configuration namespace, preserves their content and attributes, and drops the source schema hint. Write the output beside the source if it uses relative resource paths.

**CLI** (from the repository root; requires `xsltproc`):

```sh
xsltproc --nonet --output fop.xconf tools/normalize-fop-config.xsl namespaced.xconf
fop -c fop.xconf -fo document.fo -pdf document.pdf
```

**Java** (standard JAXP; no extra dependency):

```java
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

static void normalizeFopConfig(Path source, Path target) throws TransformerException {
    var factory = TransformerFactory.newDefaultInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    var converter = factory.newTransformer(
            new StreamSource(Path.of("tools", "normalize-fop-config.xsl").toFile()));
    converter.transform(new StreamSource(source.toFile()), new StreamResult(target.toFile()));
}
```

Use different source and output paths. Call `normalizeFopConfig(Path.of("namespaced.xconf"), Path.of("fop.xconf"))` from the repository root, or adjust the stylesheet path. Pass the converted file to `FopConfParser` or the FOP CLI.

## Run the verification project

Install JDK 21, then run this from the repository root:

```sh
./verification/gradlew -p verification test
```

On Windows, run `verification\gradlew.bat -p verification test`.

The tests compile every schema with local imports, validate the example XML, run FOP, and inspect PDF pages, text, annotations, structure, metadata, fonts and encryption. Open `verification/build/reports/tests/test/index.html` for the test report or `verification/build/examples/` for the generated PDFs. See the [examples](docs/examples.md) for what each PDF proves.

## Generate the compatibility report

Markdown is the default and uses standard headings and lists. Save it to compare releases, open standalone HTML in a browser, or print wrapped text in the terminal:

```sh
./verification/gradlew -q -p verification compatibilityReport > fop-compatibility.md
./verification/gradlew -q -p verification compatibilityReport -PreportFormat=html > fop-compatibility.html
./verification/gradlew -q -p verification compatibilityReport -PreportFormat=text
```

The report lists known gaps, the FOP version checked, and the evidence behind each entry. **Unsupported** means FOP does not implement the feature or falls back to different behavior; **partial** means it works in specific cases. **Not modeled** describes this project's schema scope, not FOP support. All formats come from the same XSD annotations.

Run the probes against a candidate release without changing those annotations. Substitute the version you want to check:

```sh
./verification/gradlew -p verification test -PfopVersion=X.Y --tests '*FopGapTest'
```

A failure names the feature to recheck. The report still describes the pinned release until you update the XSD annotations. A changed registration or diagnostic alone does not establish support.
