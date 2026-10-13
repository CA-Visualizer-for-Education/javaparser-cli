plugins {
    id("java")
    id("application")
    id("com.gradleup.shadow") version "9.6.1"
}

group = "org.csc494"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation("com.github.javaparser:javaparser-core:3.28.2")
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.28.2")
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")

    // Annotation processor for picocli command-line option generation
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
}

application {
    mainClass.set("com.csc494.JavaParserCLI.Main") 
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    manifest {
        attributes("Main-Class" to "com.csc494.JavaParserCLI.Main")
    }
}

