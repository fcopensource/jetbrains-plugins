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
        // Commits are read from the IDE's own Git plugin (optional: Aura works without it).
        bundledPlugin("Git4Idea")
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
              <li>Earn aura for clearing errors, clean saves, commits with good messages, green tests, builds, big deletes and locked-in sessions</li>
              <li>Roast mode: lose a little for "wip" commit messages, failing tests and builds, and leftover debugger statements</li>
              <li>Eight ranks from NPC to Final Boss, a daily streak, and daily limits so aura can't be farmed</li>
              <li>Your aura in the status bar, the Aura tool window, and a shareable 1200 × 630 aura card</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            // The IDE versions JetBrains recommends checking for our since-build: the oldest supported and the newest.
            recommended()
        }
    }
    publishing {
        // Set by CI from the JETBRAINS_MARKETPLACE_TOKEN secret; never stored in the repository.
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    test {
        // The rules tests are plain JUnit; the service test starts a light IDE. Keep the test JVM small.
        maxHeapSize = "768m"
        jvmArgs("-XX:ReservedCodeCacheSize=96m", "-XX:MaxMetaspaceSize=384m", "-Xss1m")
    }
}
