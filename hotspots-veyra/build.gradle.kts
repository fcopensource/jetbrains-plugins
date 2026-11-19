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
              <li>A heat map of your codebase from its own git history: every file sized by its code and coloured by risk</li>
              <li>Top hotspots, files that change together and knowledge risks, in the Hotspots tool window</li>
              <li>Five colour modes: hotspots, change frequency, complexity, code age and knowledge risk</li>
              <li>Flames on the hottest files in the Project view and the open file's rank in the status bar</li>
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
        // The engine tests are plain JUnit; keep the test JVM small.
        maxHeapSize = "384m"
        jvmArgs("-XX:ReservedCodeCacheSize=64m", "-XX:MaxMetaspaceSize=256m", "-Xss512k")
    }
}
