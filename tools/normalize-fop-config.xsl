<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:fop="http://xmlgraphics.apache.org/fop/config"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                exclude-result-prefixes="fop xsi">
    <xsl:output method="xml" encoding="UTF-8"/>

    <xsl:template match="@*|node()">
        <xsl:copy><xsl:apply-templates select="@*|node()"/></xsl:copy>
    </xsl:template>

    <!-- Use expanded names: both prefixed and default-namespace inputs work. -->
    <xsl:template match="fop:*">
        <xsl:element name="{local-name()}" namespace="">
            <xsl:apply-templates select="@*|node()"/>
        </xsl:element>
    </xsl:template>

    <!-- This hint describes the source vocabulary, not the converted output. -->
    <xsl:template match="fop:fop/@xsi:schemaLocation"/>
</xsl:stylesheet>
