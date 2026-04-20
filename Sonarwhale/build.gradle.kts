import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.intellij.platform.gradle.Constants
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.10.4"     // See https://github.com/JetBrains/intellij-platform-gradle-plugin/releases
    id("me.filippov.gradle.jvm.wrapper") version "0.14.0"
}

val isWindows = Os.isFamily(Os.FAMILY_WINDOWS)
extra["isWindows"] = isWindows

val RiderPluginId: String by project
val ProductVersion: String by project
val PublishToken: String by project
val PythonPluginVersion: String by project

allprojects {
    repositories {
        maven { setUrl("https://cache-redirector.jetbrains.com/maven-central") }
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

tasks.wrapper {
    gradleVersion = "8.8"
    distributionType = Wrapper.DistributionType.ALL
    distributionUrl = "https://cache-redirector.jetbrains.com/services.gradle.org/distributions/gradle-${gradleVersion}-all.zip"
}

version = extra["PluginVersion"] as String

tasks.processResources {
    from("dependencies.json") { into("META-INF") }
}

sourceSets {
    main {
        java.srcDir("src/rider/main/java")
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
    test {
        kotlin.srcDir("src/rider/test/kotlin")
    }
}

tasks.compileKotlin {
    kotlinOptions { jvmTarget = "21" }
}

tasks.test {
    useJUnitPlatform()
}

tasks.buildPlugin {
    doLast {
        copy {
            from("${buildDir}/distributions/${rootProject.name}-${version}.zip")
            into("${rootDir}/output")
        }

        // TODO: See also org.jetbrains.changelog: https://github.com/JetBrains/gradle-changelog-plugin
        val changelogText = file("${rootDir}/CHANGELOG.md").readText()
        val changelogMatches = Regex("(?s)(-.+?)(?=##|$)").findAll(changelogText)
        val changeNotes = changelogMatches.map {
            it.groups[1]!!.value.replace("(?s)- ".toRegex(), "\u2022 ").replace("`", "").replace(",", "%2C").replace(";", "%3B")
        }.take(1).joinToString()
    }
}

dependencies {
    intellijPlatform {
        rider(ProductVersion, useInstaller = false)
        jetbrainsRuntime()
        // Python Community Edition — installed in every sandbox; active in runIdePython
        plugin("PythonCore:${PythonPluginVersion}")
    }
    implementation("org.mozilla:rhino:1.7.15")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Disable the generic runIde — use the language-specific configs below instead
tasks.runIde {
    enabled = false
}

// ---------------------------------------------------------------------------
// Helper: register a named run configuration for a given language.
//
// Each task points at the same sandbox that prepareSandbox fills, so the
// Sonarwhale plugin (and PythonCore) are always present without any extra
// copying. State isolation (recent projects, IDE settings) can be added later
// via explicit idea.config.path / idea.system.path JVM args if needed.
// ---------------------------------------------------------------------------
fun registerLanguageRunConfig(label: String) {
    val mainSandboxDir = tasks.named<PrepareSandboxTask>(Constants.Tasks.PREPARE_SANDBOX)
        .flatMap { it.sandboxDirectory }

    tasks.register<RunIdeTask>("runIde$label") {
        description = "Run Rider for $label projects"
        dependsOn(Constants.Tasks.PREPARE_SANDBOX)
        sandboxDirectory.set(mainSandboxDir)
        splitMode.set(false)
        splitModeTarget.set(SplitModeAware.SplitModeTarget.BACKEND)
        maxHeapSize = "1500m"
    }
}

registerLanguageRunConfig("CSharp")   // C# — built into Rider
registerLanguageRunConfig("Java")     // Java — built into Rider
registerLanguageRunConfig("Python")   // Python — via PythonCore plugin

tasks.patchPluginXml {
    // TODO: See also org.jetbrains.changelog: https://github.com/JetBrains/gradle-changelog-plugin
    val changelogText = file("${rootDir}/CHANGELOG.md").readText()
    val changelogMatches = Regex("(?s)(-.+?)(?=##|\$)").findAll(changelogText)

    changeNotes.set(changelogMatches.map {
        it.groups[1]!!.value.replace("(?s)\r?\n".toRegex(), "<br />\n")
    }.take(1).joinToString())
}

tasks.publishPlugin {
    dependsOn(tasks.buildPlugin)
    token.set("${PublishToken}")
}
