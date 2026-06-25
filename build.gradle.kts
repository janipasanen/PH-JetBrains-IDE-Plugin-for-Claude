import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )

        // Tooling. (runIde uses the JBR bundled with the resolved IDE distribution.)
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // No searchable options yet; skip the slow index build.
    buildSearchableOptions = false

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            // Cross-IDE range: Rider (RD), WebStorm (WS), Android Studio (AI) on branch 261.
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    // Plugin signing for releases (M3). Secrets hold PEM *content* (CI-friendly); unset locally.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Marketplace publishing (only if ever published; personal use installs from disk).
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            // Verify the single artifact against the actual target IDEs (all branch 261).
            // useInstaller = false uses the repackaged repo artifacts (required for Rider, etc.).
            create("WS", "2026.1") { useInstaller = false } // WebStorm
            create("RD", "2026.1") { useInstaller = false } // Rider
            create("IU", "2026.1") { useInstaller = false } // IntelliJ IDEA (compile target)
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    wrapper {
        gradleVersion = "9.6"
    }
}
