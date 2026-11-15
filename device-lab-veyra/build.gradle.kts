import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Use the IDE interfaces' Java default methods directly. Otherwise Kotlin copies them into our classes
        // (ToolWindowFactory.getIcon, getAnchor, manage…), which the Plugin Verifier reports as internal API usage.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
        changeNotes = """
            <h3>1.0.0</h3>
            <ul>
              <li>Device Lab: theme, font size up to 200 %, display size, app language with pseudolocales, right-to-left, animations, layout bounds, show taps, airplane mode and a clean status bar, in one click</li>
              <li>Presets: Worst case, Screenshot ready, Slow motion, and Reset to put the device back as it was</li>
              <li>Your app: grant or revoke permissions, open the deep links from your manifest and Navigation graphs, restart, clear data, screenshot</li>
              <li>Emulators and real phones, Android 8 and newer; finds adb by itself</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            // The IDE versions JetBrains recommends checking for our since-build, and the latest Android Studio.
            recommended()
            create(IntelliJPlatformType.AndroidStudio, providers.gradleProperty("androidStudioVersion"))
        }
    }
    publishing {
        // Set by CI from the JETBRAINS_MARKETPLACE_TOKEN secret; never stored in the repository.
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    test {
        // The panel test starts a small test IDE.
        maxHeapSize = "512m"
        jvmArgs("-XX:ReservedCodeCacheSize=64m", "-XX:MaxMetaspaceSize=256m", "-Xss512k")
        // The device tests use a running emulator or phone when there is one (DEVICE_LAB_SERIAL picks which).
        environment("DEVICE_LAB_SERIAL", System.getenv("DEVICE_LAB_SERIAL") ?: "")
    }
}
