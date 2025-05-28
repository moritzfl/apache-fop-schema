# Schemas for Apache FOP

## Current state of IDE and validation support for XSL-FO and Apacha FOP
Apache FOP refers to an unofficial fo schema for validation (see https://xmlgraphics.apache.org/fop/fo.html - Validating XSL-FO). However, this schema does not include documentation for all relevant objects and does not include the Apache FOP Extensions.

Furthermore, I do not know of a schema for the configuration of Apache FOP - there is not even a namespace for that.

## What this project aims to achieve

This project aims to provide current, well documented and complete schemas for working with XSL-FO and Apache FOP specifically.

xsd schemas can be integrated in many IDEs and code editors and provide a simple means to access documentation while providing validation. I use the schema files with IntelliJ IDEA but they are not written or designed to only target that IDE.

## Included Schemas
- `fo.xsd`: main schema for creating templates in XSL-FO
- `fox.xsd`: schema covering Apache FOP specific extensions of XSL-FO
- `fop-config.xsd`: schema for the creation of configuration files for Apache FOP. Uses the unofficial namespace `http://xmlgraphics.apache.org/fop/config` 
- `rdf.xsd`, `dc.xsd`, `xmpmeta.xsd`: schemas related to certain elements needed for working with PDF files - not directly linked to Apache FOP

## Working with Apache FOP configuration files

While Apache FOP is configured through an XML file, there is no official schema and no official namespace that the configuration uses. In order to create configuration files, you can use the schema `fop-config.xsd`. However, you have to pay attention not to break the configuration for Apache FOP as it does not expect namespace-specific tags.

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
