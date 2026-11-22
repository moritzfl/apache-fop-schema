# Schema contribution checks

The schemas in [`schema/`](../schema/) are intended for namespace-aware XML authoring, validation, and offline use. Keep these properties when adding or changing a schema:

| Requirement | How it is checked |
| --- | --- |
| XML Schema 1.0, valid UTF-8, no vendor-specific syntax | Compile every `.xsd` independently with the JDK's XSD 1.0 processor, from a relocated copy of the schema set. |
| Imports resolve in a clean checkout without a network, catalog, or absolute paths | Require relative file names, check imported target namespaces, and validate the example files using only their own schema hints. |
| Context-aware child discovery | Use explicit element references and named groups. Do not hide a known vocabulary behind a wildcard or substitution group. Restrict wildcards to intentionally extensible content such as embedded SVG or custom XMP properties. |
| Attribute and value discovery | Put attributes on the relevant elements or named groups, require mandatory values, and use `xs:enumeration` for small closed vocabularies. Preserve strings for FO expressions that FOP must evaluate. |
| Documentation on each named declaration | Put nonempty `xs:annotation/xs:documentation` on each schema and named element, attribute, type and group. Lead with the observable effect, not a restatement of the name. Say where it applies, what omitting it does, and give a literal input example when the behavior or syntax is unclear. Separate standard behavior from FOP limitations. An XML comment is not a substitute. |
| Errors are visible | Negative examples exercise invalid child names, spelling mistakes, misplaced attributes, namespace errors, missing required values, and wrong enumerations. |
| Compatibility claims are versioned | Annotate limitations at the declaration (or at schema level for omitted features). Tests check annotation format, baseline version, and probe coverage. |

These checks verify the portable schema features needed by authoring tools; they do not run a particular editor's UI. XSD 1.0 cannot encode every contextual FO expression or renderer-specific rule. For example, an XML schema can check that `internal-destination` is written on a bookmark, but it cannot prove that the target generates an area reachable in the PDF.

For a new tested feature, add or extend an example under `verification/examples/`, then assert the specific expected behavior in the generated PDF. On success, the suite should produce no unexpected FOP warnings. For a known fallback, assert the expected diagnostic or unchanged output separately.

## Record a compatibility gap

Declare `xmlns:compat="urn:apache-fop-schema:compatibility"` on the schema. Add metadata to the same `xs:documentation` readers see:

```xml
<xs:documentation compat:feature="caption-side" compat:status="unsupported"
                  compat:fop="2.12" compat:evidence="pdf-test">
    Position of a caption relative to its table.

    FOP 2.12: Unsupported. The caption renders before the table.
</xs:documentation>
```

- `compat:feature`: unique feature name. Include the specific value or context if the whole property is not affected.
- `compat:status`: `unsupported`, `partial`, `plug-in-required`, or `not-modeled`.
- `compat:fop`: FOP release checked. The last paragraph starts with the matching readable version and status, e.g. `FOP 2.12: Partial.` Separate it from the general description with a blank line.
- `compat:evidence`: space-separated `pdf-test`, `area-tree-test`, `runtime-probe`, `source`, `compliance`, `upstream-docs`, or `schema-scope`. An area-tree test checks layout before rendering. A runtime probe checks a registration, stub, or diagnostic, which alone does not establish PDF behavior. Cite a specific upstream page using `xs:documentation/@source` when useful.

Use a top-level annotation for a feature omitted from the schema. Verify each renderer gap in the pinned FOP source and give it a named probe in `FopGapTest`. Trace the feature past parsing to layout or PDF output where applicable. Prefer a PDF or area-tree assertion with a control; use a runtime diagnostic or implementation check when it gives a clearer signal. `not-modeled` entries describe schema scope, not FOP support.

Run the probes against the candidate release before updating the version catalog. Review changed probes and upstream release notes, update the XSD annotations, then regenerate the [report](../README.md#generate-the-compatibility-report).
