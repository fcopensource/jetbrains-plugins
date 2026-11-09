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
              <li>Every listening port in a Ports tool window, grouped into This Project, Apps &amp; Dev Servers, Editors &amp; Tools and System</li>
              <li>Recognises 40+ dev servers and tools: Vite, Next.js, Django, Spring Boot, Rails, PostgreSQL, Docker and more</li>
              <li>Open in Browser, Copy Address, Stop and Reveal Folder; Free a Port… stops whatever holds a port</li>
              <li>A status-bar counter and a notification when your project's server starts</li>
              <li>System services, other users' programs and JetBrains IDEs are protected and can never be stopped</li>
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
        // Tests start real servers and child processes; give them a little room.
        maxHeapSize = "256m"
        jvmArgs("-XX:ReservedCodeCacheSize=64m", "-XX:MaxMetaspaceSize=256m", "-Xss512k")
    }
}
