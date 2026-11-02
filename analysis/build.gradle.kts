plugins {
    id("triplea-java-library")
}

// Reads only the runner's raw data files, never the engine, so any run folder can be re-analysed.
dependencies {
    compileOnly(libs.jsr305)
}

val outRoot = rootProject.layout.projectDirectory.dir("build").asFile.absolutePath

tasks.register<JavaExec>("tabulate") {
    group = "ai benchmark"
    description = "Compares run folders' raw data (-PrunDirs=a,b,..., default: latest run)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.triplea.ai.benchmark.tabulator.TabulatorMain"
    args(providers.gradleProperty("runDirs").getOrElse("$outRoot/LATEST").split(","))
}
