# Examples and recipes

Run the [Gradle suite](../README.md#run-the-verification-project) to generate the example files. The tests check output with Java libraries, not external tools.

| Example | Verified output |
| --- | --- |
| [Navigation](../verification/examples/navigation/document.fo) | Two A4 pages, page numbers and last-page citations, PDF outline, a named destination, and internal and external link annotations. |
| [Running header and appendix](../verification/examples/running-header/document.fo) | Repeated header text from `fo:marker`, a visible change bar, and a 90 × 60 pt SVG appendix occupying a whole page. |
| [Tagged metadata](../verification/examples/tagged-metadata/) | Default page size, PDF version and stream filters, tagged heading and figure, image/link alternatives and abbreviation expansion, translated XMP title, PDF Info and duplex preference. |
| [Printable table](../verification/examples/print-table/document.fo) | Trim, bleed, media and crop boxes, visible table and caption, plus a test showing `caption-side="after"` is ignored in FOP 2.12. |
| [PDF/UA and fonts](../verification/examples/pdf-ua/) | PDF/UA-1 identification and language, full versus subset font programs, and extracted accented text. The suite uses Liberation Sans from the pinned PDFBox artifact; for manual CLI use, put your licensed `LiberationSans-Regular.ttf` beside `fop.xconf`. |
| [Encryption](../verification/examples/encrypted/fop.xconf) | AES-256 configuration requires a password; PDFBox checks restricted user and unrestricted owner permissions. Uses the navigation FO and public example passwords. |
| [Namespaced configuration](../verification/examples/namespaced-config/fop.xconf) | The default namespace works directly and sets page size and tagging. The [prefixed form](../verification/examples/namespaced-config/prefixed.xconf) validates but needs the [normalizer](../README.md#normalize-a-namespaced-configuration) for FOP to apply its settings. |
| [AFP, PostScript, and PNG](../verification/examples/renderer-output/) | AFP page size and resolution from structured fields (afplib); PostScript language level and page bounds from DSC comments (XML Graphics Commons); PNG dimensions, fill color, and alpha (ImageIO). |

The suite also enables PDF object streams on the navigation document and checks the actual object and cross-reference streams.

The AFP and PostScript checks verify file structure and renderer settings, not visual output or printer behavior. FOP warns about default AFP font setup even though this fixture has no text; the test allows only that warning.

## Validate FO and keep strict rendering

For a document with no inline schema hint, select [`fo.xsd`](../schema/fo.xsd) in your XML tools or run this from the repository root:

```sh
xmllint --nonet --noout --schema schema/fo.xsd document.fo
```

To select the schema from the FO file, set `xsi:schemaLocation` to the namespace followed by the relative path to `fo.xsd`. The example assumes the document is in the repository root:

```xml
<fo:root xmlns:fo="http://www.w3.org/1999/XSL/Format"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://www.w3.org/1999/XSL/Format schema/fo.xsd">
    <!-- layout-master-set and page-sequence go here -->
</fo:root>
```

**FOP 2.12 strict mode rejects the inline FO hint.** For command-line rendering, strip only the schema hints from FO elements; do not turn off strict validation. Keep the converted file beside the input when relative resources matter.

```sh
xsltproc --nonet --output render.fo tools/strip-fo-schema-hints.xsl document.fo
fop -fo render.fo -pdf document.pdf
```

Embedded applications can instead configure `FopFactoryBuilder` to ignore that namespace when interpreting FO properties:

```java
var builder = new FopFactoryBuilder(baseUri);
builder.ignoreNamespace(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
FopFactory fopFactory = builder.build();
```

When loading a configuration file, use `new FopConfParser(configFile).getFopFactoryBuilder()` to obtain the builder before applying the same setting.

## Layout and navigation

- For A4 use `page-width="210mm" page-height="297mm"`. For application defaults use `page-width="auto" page-height="auto"` and `<default-page-settings width="210mm" height="297mm"/>` in the config. The FO `size` shorthand and `indefinite` page dimensions are not implemented in FOP 2.12.
- For a running header, reserve room in the body region and define a before region. A header line of `14.4pt` does not fit in a `14pt` region. Match `flow-name="xsl-region-before"` to the region; a marker supplies the changing part of the text.
- Use `fo:bookmark-tree` after `fo:declarations` and before page sequences. A bookmark creates the PDF outline entry. Use `fo:basic-link` for a clickable region in the body and `fox:destination` to expose a named PDF destination. All three can point to the same FO `id`.
- For “Page X of Y”, assign an ID to the page sequence and use `fo:page-number-citation-last ref-id="that-id"` for Y. This reports the last displayed page number, not necessarily the count if numbering starts above 1.
- For duplex printing, FOP 2.12 adds `force-page-count="doubly-even"` (sequence length divisible by four) and `"doubly-odd"` (one short of a multiple of four). Use `"end-on-doubly-even"` or `"end-on-doubly-odd"` to apply the rule to the final displayed page number instead. FOP inserts blank pages as needed.
- For a square-mark leader, use `<fo:leader leader-pattern="rule" fox:rule-style="square" rule-thickness="2pt" leader-length.optimum="100%"/>`. For standard dotted or dashed leaders, use the unqualified `rule-style` instead.

## Metadata and accessible output

Under `fo:declarations`, write `x:xmpmeta/rdf:RDF/rdf:Description` with `rdf:about=""`. FOP also accepts a bare `rdf:RDF` there. FOP 2.12 only renders the first XMP packet, so combine descriptions in one packet. Use `rdf:Alt` with `xml:lang="x-default"` for the primary `dc:title` and `dc:description`, `rdf:Seq` for ordered authors (`dc:creator`), and `rdf:Bag` for tags such as `dc:language`. FOP copies the default title, creator and description into the PDF Info dictionary. `dc:subject` is a list of topics, not the PDF Info Subject.

For tagged output, set `<accessibility>true</accessibility>`, set FO `language`/`country` (or a simple `xml:lang` such as `en-GB`), and write meaningful element roles and graphic alternatives:

```xml
<fo:block role="H1">Monthly sales</fo:block>
<fo:block>
    <fo:external-graphic src="url('sales.svg')"
        fox:alt-text="Sales rose from 20 to 35 units."/>
</fo:block>
```

For PDF/UA-1, also set `<pdf-ua-mode>PDF/UA-1</pdf-ua-mode>` on the PDF renderer and embed the fonts. To compare files, switch `embedding-mode="full"` to `"subset"` on the same `<font>` entry. PDFBox tests check structure, metadata and font data; use a dedicated conformance validator and manual accessibility review for production documents. Do not infer conformance from the presence of a profile identifier.

## Renderer settings

- To keep PDF content streams readable while still compressing images, use `<filterList><value>null</value></filterList>` and `<filterList type="image"><value>flate</value></filterList>`. Use one list per stream category.
- To enable object streams, set `<use-object-streams>true</use-object-streams>` and choose PDF 1.5 or newer, for example `<version>1.7</version>`.
- PDF encryption belongs under `<renderer mime="application/pdf"><encryption-params>…`. Use empty permission flags such as `<noprint/>`: FOP checks their presence, so `<noprint>false</noprint>` still restricts printing and is rejected by the schema. PDF/A does not allow encryption.
- XSD 1.0 does not dispatch renderer settings by `mime` and accepts repeated options to allow order-independent config and lists. Set each singleton once. Use the [Apache FOP configuration reference](https://xmlgraphics.apache.org/fop/2.12/fopxconf.html) for renderer-specific settings and test the resulting output.
