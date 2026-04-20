import org.apache.tools.ant.taskdefs.condition.Os

plugins {
    id("java")
    alias(libs.plugins.kotlinJvm)
    id("org.jetbrains.intellij.platform") version "2.10.4"
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
}

tasks.compileKotlin {
    kotlinOptions { jvmTarget = "21" }
}

tasks.buildPlugin {
    doLast {
        copy {
            from("${buildDir}/distributions/${rootProject.name}-${version}.zip")
            into("${rootDir}/output")
        }

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
    }
}

// ---------------------------------------------------------------------------
// Language-specific run configurations via the official testing extension.
// Each gets its own prepareSandbox_* task with correct plugin wiring.
// ---------------------------------------------------------------------------
intellijPlatformTesting {
    runIde {
        register("runIdeCSharp") {
            task {
                maxHeapSize = "1500m"
            }
        }
        register("runIdeJava") {
            task {
                maxHeapSize = "1500m"
            }
        }
        register("runIdePython") {
            task {
                maxHeapSize = "1500m"
            }
            plugins {
                plugin("PythonCore:${PythonPluginVersion}")
            }
        }
    }
}

tasks.patchPluginXml {
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