import xyz.jpenilla.runpaper.task.RunServer

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

evaluationDependsOn(":tester")

dependencies {
    api(project(":api"))
    api(project(":common"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
    // The server supplies netty at runtime; the boxer connection's capture
    // handler compiles against it directly.
    compileOnly(libs.netty.all)
    implementation(libs.reflection.remapper)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.paper.api.floor)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("SimpleBoxer")
    archiveClassifier.set("")

    relocate("xyz.jpenilla.reflectionremapper", "me.vexmc.simpleboxer.lib.reflectionremapper")
    relocate("net.fabricmc.mappingio", "me.vexmc.simpleboxer.lib.mappingio")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

/* ────────────────────────────────────────────────────────────────────────
 *  Real-server integration matrix (ported from Mental)
 *
 *  For every version in gradle.properties' integrationTestVersions, a real
 *  Paper server boots with the SimpleBoxer and SimpleBoxerTester jars
 *  installed; the tester runs its suite in-process, writes PASS/FAIL, and
 *  shuts the server down; the paired check task fails the build on anything
 *  but PASS.
 *
 *  When run/mental-jar/Mental.jar (and run/ocm-jar/OldCombatMechanics.jar)
 *  exist, floor and ceiling also boot WITH those plugins — the coexistence
 *  acceptance this plugin exists for.
 * ──────────────────────────────────────────────────────────────────────── */

val integrationTestVersions: List<String> =
    (findProperty("integrationTestVersions") as String?)
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: listOf("1.17.1", "26.1.2")

fun parseMinecraftVersion(version: String): Triple<Int, Int, Int> {
    val parts = version.split(".")
    return Triple(
        parts.getOrNull(0)?.toIntOrNull() ?: 0,
        parts.getOrNull(1)?.toIntOrNull() ?: 0,
        parts.getOrNull(2)?.toIntOrNull() ?: 0,
    )
}

// 1.17–1.20.4 class files target Java 17; 1.20.5+ requires 21 and runs
// happily on 25 — two toolchains cover the whole matrix.
fun requiredJavaVersion(version: String): Int {
    val (major, minor, patch) = parseMinecraftVersion(version)
    if (major > 1 || minor > 20 || (minor == 20 && patch >= 5)) return 25
    return 17
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val testerShadowJar = project(":tester").tasks.named<AbstractArchiveTask>("shadowJar")

tasks.named<RunServer>("runServer") {
    enabled = false
    description = "Disabled — use integrationTest or integrationTestMatrix."
}

val checkTasks = mutableListOf<TaskProvider<Task>>()
var previousCheck: TaskProvider<Task>? = null

/**
 * One live-server suite: boots Paper [version] in run/[runDirName] with
 * SimpleBoxer, the tester, and any [extraPluginJars]; the paired check task
 * fails the build unless the tester wrote PASS. Run tasks are chained
 * sequentially by the caller — every server binds the same port.
 */
fun registerIntegrationServer(
    taskSuffix: String,
    version: String,
    runDirName: String,
    extraPluginJars: List<File>,
    flavour: String,
): Pair<TaskProvider<RunServer>, TaskProvider<Task>> {
    val runDir = rootProject.layout.projectDirectory.dir("run/$runDirName").asFile
    val resultFile = runDir.resolve("plugins/SimpleBoxerTester/test-results.txt")
    val failuresFile = runDir.resolve("plugins/SimpleBoxerTester/test-failures.txt")
    val logFile = layout.buildDirectory.file("integration-test-logs/${runDirName.replace('/', '-')}.log")
    val label = version + flavour

    val runTask = tasks.register<RunServer>("runIntegrationTest$taskSuffix") {
        group = "simpleboxer integration"
        description = "Boots Paper $label with SimpleBoxer + tester and runs the suite."
        dependsOn(tasks.shadowJar, testerShadowJar)
        // A server boot is never up-to-date — the suite result IS the output,
        // and it lives outside Gradle's tracked outputs by design.
        outputs.upToDateWhen { false }
        runDirectory.set(runDir)
        minecraftVersion(version)
        // disable.watchdog matters on slow CI runners: a >60s tick stall
        // trips the legacy watchdog, whose forced shutdown can deadlock old
        // servers into a hung process that never writes a test result.
        jvmArgs("-Dcom.mojang.eula.agree=true", "-Ddisable.watchdog=true", "-Xmx2G", "-Dsimpleboxer.debug=true")
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(requiredJavaVersion(version)))
        })
        pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
        pluginJars.from(testerShadowJar.flatMap { it.archiveFile })
        extraPluginJars.forEach { pluginJars.from(it) }

        doFirst {
            resultFile.delete()
            failuresFile.delete()
            // The whole config tree resets per run so every suite starts
            // from the defaults; companion plugins start pristine too —
            // their defaults are part of what the coexistence legs assert.
            runDir.resolve("plugins/SimpleBoxer").deleteRecursively()
            runDir.resolve("plugins/Mental").deleteRecursively()
            runDir.resolve("plugins/OldCombatMechanics").deleteRecursively()
            val properties = runDir.resolve("server.properties")
            if (!properties.exists()) {
                runDir.mkdirs()
                properties.writeText(
                    """
                    level-type=flat
                    online-mode=false
                    spawn-protection=0
                    view-distance=4
                    simulation-distance=4
                    spawn-monsters=false
                    motd=SimpleBoxer integration test
                    """.trimIndent() + "\n"
                )
            } else {
                // Long-lived run dirs predate the hostile-mob embargo (the
                // tester's TestEnvironment): a naturally spawned creeper or
                // skeleton can kill a mortal boxer mid-case. Pin the property
                // here too so even the boot-to-settle window spawns nothing.
                val lines = properties.readLines()
                val pinned = lines.map {
                    if (it.startsWith("spawn-monsters=")) "spawn-monsters=false" else it
                }.let {
                    if (it.none { line -> line.startsWith("spawn-monsters=") }) {
                        it + "spawn-monsters=false"
                    } else {
                        it
                    }
                }
                if (pinned != lines) {
                    properties.writeText(pinned.joinToString("\n") + "\n")
                }
            }
            val log = logFile.get().asFile
            log.parentFile.mkdirs()
            val stream = log.outputStream()
            standardOutput = stream
            errorOutput = stream
        }
        doLast {
            (standardOutput as? java.io.Closeable)?.close()
        }
    }

    val checkTask = tasks.register("checkIntegrationTest$taskSuffix") {
        group = "simpleboxer integration"
        description = "Verifies the $label suite reported PASS."
        dependsOn(runTask)
        doLast {
            val log = logFile.get().asFile
            if (!resultFile.exists()) {
                throw GradleException(
                    "No test result for $label — server crashed or hung. Log: ${log.absolutePath}")
            }
            if (failuresFile.exists()) {
                failuresFile.readLines().filter { it.isNotBlank() }.take(10).forEach {
                    logger.lifecycle("[$label] FAILURE: $it")
                }
            }
            when (val result = resultFile.readText().trim()) {
                "PASS" -> logger.lifecycle("[$label] integration tests passed. Log: ${log.absolutePath}")
                "FAIL" -> throw GradleException("Integration tests failed for $label. Log: ${log.absolutePath}")
                else -> throw GradleException("Unknown test result '$result' for $label.")
            }
        }
    }
    return runTask to checkTask
}

integrationTestVersions.forEach { version ->
    val suffix = "_" + version.replace(".", "_")
    val (runTask, checkTask) = registerIntegrationServer(suffix, version, version, emptyList(), "")
    previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
    previousCheck = checkTask
    checkTasks.add(checkTask)
}

/* ────────────────────────────────────────────────────────────────────────
 *  Combat-plugin coexistence runs. Stage the jars:
 *    run/mental-jar/Mental.jar             (Mental repo: ./gradlew shadowJar)
 *    run/ocm-jar/OldCombatMechanics.jar    (OCM fork:    ./gradlew shadowJar)
 *  Whichever exist join the floor and ceiling boots; the tester detects
 *  them and adds the coexistence suites.
 * ──────────────────────────────────────────────────────────────────────── */
val mentalJarFile = rootProject.layout.projectDirectory.file("run/mental-jar/Mental.jar").asFile
val ocmJarFile = rootProject.layout.projectDirectory.file("run/ocm-jar/OldCombatMechanics.jar").asFile
val combatJars = listOf(mentalJarFile, ocmJarFile).filter { it.isFile }
val combatCheckTasks = mutableListOf<TaskProvider<Task>>()

if (combatJars.isNotEmpty()) {
    val flavour = buildString {
        if (mentalJarFile.isFile) append("+Mental")
        if (ocmJarFile.isFile) append("+OCM")
    }
    setOf(integrationTestVersions.first(), integrationTestVersions.last()).forEach { version ->
        val suffix = "Combat_" + version.replace(".", "_")
        val (runTask, checkTask) = registerIntegrationServer(
            suffix, version, "combat/$version", combatJars, " $flavour")
        previousCheck?.let { prior -> runTask.configure { mustRunAfter(prior) } }
        previousCheck = checkTask
        combatCheckTasks.add(checkTask)
    }
}

tasks.register("integrationTest") {
    group = "simpleboxer integration"
    description = "Runs the suite on the floor and newest supported versions."
    val floorAndCeiling = setOf(integrationTestVersions.first(), integrationTestVersions.last())
    dependsOn(checkTasks.filter { provider ->
        floorAndCeiling.any { provider.name.endsWith("_" + it.replace(".", "_")) }
    })
}

tasks.register("integrationTestMatrix") {
    group = "simpleboxer integration"
    description = "Runs the suite on every version in integrationTestVersions."
    dependsOn(checkTasks)
}

tasks.register("integrationTestCombat") {
    group = "simpleboxer integration"
    description = "Runs the Mental/OCM coexistence suite on floor and ceiling " +
            "(requires staged jars under run/mental-jar and/or run/ocm-jar)."
    if (combatCheckTasks.isNotEmpty()) {
        dependsOn(combatCheckTasks)
    } else {
        doFirst {
            throw GradleException(
                "No combat jars staged. Build Mental (./gradlew shadowJar) into " +
                        "run/mental-jar/Mental.jar and/or OldCombatMechanics into " +
                        "run/ocm-jar/OldCombatMechanics.jar first.")
        }
    }
}
