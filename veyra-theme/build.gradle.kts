import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.20.0"
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
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
    }
}

intellijPlatform {
    // A theme has no code: nothing to instrument or search-index.
    instrumentCode = false
    buildSearchableOptions = false

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
              <li>Veyra Dark and Veyra Light, in the FlightCoders colours: coral, amber and violet on a deep ink background</li>
              <li>Every part of the IDE themed, with the rounded Islands look on 2025.3 and newer</li>
              <li>Matching editor colour schemes for every language, the debugger, diffs, VCS and the terminal</li>
            </ul>
        """.trimIndent()
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
    publishing {
        // Set by CI from the JETBRAINS_MARKETPLACE_TOKEN secret; never stored in the repository.
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Opens a project in the sandbox IDE, skipping first-run dialogs, so the theme can be seen at once:
//   ./gradlew runIde -PdemoProject=/path/to/project            (IntelliJ IDEA 2024.3, the oldest we support)
//   ./gradlew runIdeLatest -PdemoProject=/path/to/project      (IntelliJ IDEA 2026.3, with the Islands look)
val demoProject: String? = providers.gradleProperty("demoProject").orNull
val quietStart = listOf(
    "-Djb.consents.confirmation.enabled=false",
    "-Djb.privacy.policy.text=<!--999.999-->",
    "-Didea.trust.all.projects=true",
    "-Dide.show.tips.on.startup.default.value=false",
)

tasks.runIde {
    if (demoProject != null) args(demoProject)
    jvmArgs(quietStart)
}

intellijPlatformTesting {
    runIde {
        register("runIdeLatest") {
            type = IntelliJPlatformType.IntellijIdea
            version = "2026.3"
            task {
                if (demoProject != null) args(demoProject)
                jvmArgs(quietStart)
            }
        }
    }
}
