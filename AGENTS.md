# Maintaining the FOP schemas

This project documents XSL-FO for Apache FOP, primarily PDF output. Treat the W3C standard, schema validation, and FOP rendering as separate contracts. A feature can be valid XSL-FO and still be ignored or approximated by FOP.

## Repository layout

| Path | Responsibility |
| --- | --- |
| `schema/fo.xsd` | Main XSL-FO entry point. Imports FOP extensions and metadata vocabularies; keep its relative imports intact. |
| `schema/fox.xsd`, `schema/pdf.xsd` | General FOP extensions and FOP's PDF extension vocabulary. The latter is not Adobe's PDF XMP namespace. |
| `schema/{xmpmeta,rdf,dc,xml}.xsd` | XMP/RDF/Dublin Core subsets used for PDF metadata, plus `xml:lang`. Keep the existing entry points available. |
| `schema/fop-config-no-namespace.xsd`, `schema/fop-config.xsd` | No-namespace and namespaced configuration entry points. Both share the declarations in `fop-config-no-namespace.xsd`. |
| `tools/` | XSLT 1.0 transforms for normalizing namespaced config and stripping schema hints before strict FO rendering. |
| `verification/` | Standalone Java 21 Gradle project. `examples/` holds runnable fixtures; `src/test/java/` holds checks; `src/main/java/` generates the compatibility report. |
| `docs/` | User recipes in `examples.md` and schema design rules in `schema-contract.md`. The XSD annotations own the compatibility data. |

## Making and verifying changes

1. Check the [W3C XSL 1.1 recommendation](https://www.w3.org/TR/xsl11/), Apache's [compliance table](https://xmlgraphics.apache.org/fop/compliance.html), and the docs and source for the FOP release pinned in `verification/gradle/libs.versions.toml`. Keep dependency versions in that catalog and use the JUnit BOM for aligned JUnit versions. Do not infer renderer support from a registered FO object, a parsed property, or a successful render.
2. Follow [the schema contract](docs/schema-contract.md): XSD 1.0, UTF-8, relative imports, explicit child references, documentation on every named declaration, and enumerations for closed vocabularies. Keep FO expressions open where the formatter must evaluate them. Use wildcards only at deliberate extension points, such as custom XMP properties or embedded graphics.
3. Add or update a small fixture in `verification/examples/`. Validate the input, then use PDFBox to check the actual effect rather than just checking that FOP wrote a PDF. Use negative cases for bad syntax and separate tests for known FOP fallbacks. Supported examples should produce no unexpected FOP warnings.
4. Mark confirmed limitations in `xs:documentation` with [structured compatibility attributes](docs/schema-contract.md#record-a-compatibility-gap). Record the FOP version and distinguish PDF tests, source findings, and published compliance claims. When bumping FOP, compare release notes with the generated report and rerun the tests before marking a gap closed. Do not maintain a separate list of gaps.

From the repository root, with JDK 21:

```sh
./verification/gradlew -p verification test
```

For a clean run without network access after downloading dependencies:

```sh
./verification/gradlew -p verification clean test --offline
```

Open `verification/build/reports/tests/test/index.html` for results and `verification/build/examples/` for generated PDFs. Do not commit generated files or depend on host-installed fonts. The font example uses Liberation Sans from the pinned PDFBox artifact.

Generate the current compatibility report from the XSDs:

```sh
./verification/gradlew -q -p verification compatibilityReport
```

## FOP behavior to remember

- **Configuration namespaces:** FOP 2.12 looks up literal tag names. Unprefixed tags work with or without a default namespace. Prefixing the tags can pass XSD validation while silently skipping settings. Use `tools/normalize-fop-config.xsl` for prefixed input or when no-namespace output is needed. Test a visible effect such as page size or tagging after changing config parsing.
- **FO schema hints:** Strict FOP 2.12 rejects `xsi:schemaLocation` on FO elements. Keep the hints in authoring fixtures. Ignore only the `xsi` namespace via `FopFactoryBuilder` when embedding, or use `tools/strip-fo-schema-hints.xsl` for CLI input. Do not turn off strict validation to work around schema hints. Keep relative-resource base URIs intact when transforming files.
- **Configuration values:** Text booleans use literal `true`/`false`, not XSD's `1`/`0`. PDF encryption permission flags such as `<noprint/>` are presence-based, even if someone puts `false` inside. `strict-configuration` does not catch every unknown or misplaced setting.
- **Layout support:** FOP 2.12 renders `fo:table-and-caption`, but ignores `caption-side`. Automatic table layout and `collapse-with-precedence` warn and fall back. These are good examples of why tests assert behavior and events, not just successful rendering.
- **Metadata and accessibility:** Put document XMP in `fo:declarations`; FOP 2.12 uses the first packet. Use `rdf:Alt` with `xml:lang="x-default"` for localized titles/descriptions and `rdf:Seq` for authors. PDF Info, XMP, and the structure tree are separate: test the one your change affects. PDFBox checks of tagging, embedded fonts, or a PDF/UA identifier do not establish PDF/UA conformance.
