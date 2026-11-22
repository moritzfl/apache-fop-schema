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

val fopBaseline = libs.versions.fop.get()
val fopVersion = providers.gradleProperty("fopVersion").orElse(fopBaseline)

dependencies {
    implementation(libs.commonmark)
    testImplementation(libs.fop) {
        version { strictly(fopVersion.get()) }
    }
    testImplementation(libs.afplib)
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
    val exampleOutput = layout.buildDirectory.dir("examples")
    inputs.dir(schemas).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(examples).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(stylesheets).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(exampleOutput)
    systemProperty("schemaDir", schemas.asFile.absolutePath)
    systemProperty("fopBaseline", fopBaseline)
    systemProperty("fopVersion", fopVersion.get())
    systemProperty("exampleDir", examples.asFile.absolutePath)
    systemProperty("stylesheetDir", stylesheets.asFile.absolutePath)
    systemProperty("exampleOutputDir", exampleOutput.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("compatibilityReport") {
    group = "documentation"
    description = "Prints the compatibility report as Markdown, HTML or readable text."
    mainClass = "org.apachefopschema.CompatibilityReport"
    classpath = sourceSets.main.get().runtimeClasspath
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
    args(schemas.asFile.absolutePath, providers.gradleProperty("reportFormat").orElse("markdown").get())
}
