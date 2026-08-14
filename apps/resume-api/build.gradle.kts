plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Download records: a handful of rows on a personal site. A file is the
    // right size of database here — no server to run, no schema migrations.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // Reads the source resume, locates the text to hide, and rasterises the
    // result. See PdfMasker for why rasterising is the point and not a shortcut.
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
