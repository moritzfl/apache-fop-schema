# Schemas for Apache FOP

Apache FOP referes to an unofficial fo schema for validatio (see https://xmlgraphics.apache.org/fop/fo.html - Validating XSL-FO). However, this schema does not include documentation for all relevant objects and does not include the Apache FOP Extensions.

Furthermore, I do not know of a schema for the configuration of Apache FOP - there is not even a namespace for that.

This is why I created a bunch of xsd schemas for use in the IDE of your choice (currently tested with IntelliJ IDEA). They might not be perfect so pull requests are highly welcome.

## Included Schemas
- `fo.xsd`: main schema for creating templates in XSL-FO
- `fox.xsd`: schema covering Apache FOP specific extensions of XSL-FO
- `fop-config.xsd`: schema for the creation of configuration files for Apache FOP. Uses the unofficial namespace `http://xmlgraphics.apache.org/fop/config` 
- `rdf.xsd`, `dc.xsd`, `xmpmeta.xsd`: schemas related to certain elements needed for working with PDF files - not directly linked to Apache FOP

## Working with Apache FOP configuration
- you may use a configuration like this in Apache FOP - Apache FOP won't be disturbed by the namespace declaration (tested with Apache FOP 2.10)
```xml
<fop xmlns="http://xmlgraphics.apache.org/fop/config" version="1.0">
    <renderers>
        <renderer mime="application/pdf"/>
    </renderers>
</fop>
```
- you can NOT use a configuration like this in Apache FOP - Apache FOP will be disturbed by the fop: prefixes on each element tag.
```xml
<fop:fop xmlns:fop="http://xmlgraphics.apache.org/fop/config" version="1.0">
    <fop:renderers>
        <fop:renderer mime="application/pdf"/>
    </fop:renderers>
</fop:fop>

```
