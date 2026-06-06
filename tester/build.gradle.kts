plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":common"))
    compileOnly(project(":core"))
    compileOnly(libs.paper.api.floor)
    compileOnly(libs.jetbrains.annotations)
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveBaseName.set("SimpleBoxerTester")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
