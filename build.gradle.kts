import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// -PplatformVersion=<version> builds and tests against another IDE (2025.3+ ships as a single IntelliJ IDEA).
// IDEs from 2026.1 are compiled for Java 25, so that run uses a Java 25 toolchain (e.g. the IDE's bundled JBR via
// -Porg.gradle.java.installations.paths=<idea>/jbr); the plugin itself is always compiled for Java 21.
val platformVersion = providers.gradleProperty("platformVersion").orNull

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(if (platformVersion == null) 21 else 25)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val lombokVersion = "1.18.42"
val junitVersion = "5.13.4"

dependencies {
    intellijPlatform {
        if (platformVersion == null) {
            intellijIdeaCommunity("2025.2")
        } else {
            intellijIdea(platformVersion)
        }
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }

    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")

    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // BasePlatformTestCase is JUnit 3/4 style; the vintage engine runs it on the JUnit Platform.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
    }
    prepareTestSandbox {
        if (platformVersion != null) {
            // the unified IntelliJ IDEA cannot start its Ultimate part in tests without a subscription
            disabledPlugins.add("com.intellij.modules.ultimate")
        }
    }
    test {
        useJUnitPlatform()
        // RealHoleBinaryTest runs only with HOLE_REAL_BINARY; a changed value must not reuse cached test results
        inputs.property("holeRealBinary", providers.environmentVariable("HOLE_REAL_BINARY").orElse(""))
    }
}
