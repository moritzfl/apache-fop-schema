plugins {
    java
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    testImplementation(libs.fop)
    testImplementation(libs.pdfbox)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val schemas = layout.projectDirectory.dir("../schema")

tasks.test {
    useJUnitPlatform()
    val examples = layout.projectDirectory.dir("examples")
    val stylesheets = layout.projectDirectory.dir("../tools")
    val pdfs = layout.buildDirectory.dir("examples")
    inputs.dir(schemas).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(examples).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(stylesheets).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(pdfs)
    systemProperty("schemaDir", schemas.asFile.absolutePath)
    systemProperty("exampleDir", examples.asFile.absolutePath)
    systemProperty("stylesheetDir", stylesheets.asFile.absolutePath)
    systemProperty("pdfDir", pdfs.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}
