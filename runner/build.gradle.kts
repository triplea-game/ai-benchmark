plugins {
    id("triplea-java-library")
}

dependencies {
    implementation("triplea:game-core")
    implementation("triplea:java-extras")
    implementation(libs.sonatype.goodies.prefs)
}

val kataDir = rootProject.layout.projectDirectory.dir("kata").asFile.absolutePath
// A fixed path rather than the build directory, which a Gradle init script may relocate.
val outRoot = rootProject.layout.projectDirectory.dir("build").asFile.absolutePath

tasks.register<JavaExec>("runBenchmark") {
    group = "ai benchmark"
    description = "Plays the matching kata and writes the raw data (-Pai=, -Pkata=, -Pheap=, -Pworkers=, -Pminutes=, -Pthorough=, -Pbaseline=)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.triplea.ai.benchmark.runner.BenchmarkMain"
    workingDir = rootDir
    maxHeapSize = providers.gradleProperty("heap").getOrElse("2g")
    args(
        "--kata-dir", kataDir,
        "--out", outRoot,
        "--ai", providers.gradleProperty("ai").getOrElse("Hard Beta"),
        "--kata", providers.gradleProperty("kata").getOrElse("*"),
        "--workers", providers.gradleProperty("workers").getOrElse("0"),
        "--minutes", providers.gradleProperty("minutes").getOrElse("20"),
        "--thorough", providers.gradleProperty("thorough").getOrElse("false"),
        "--baseline", providers.gradleProperty("baseline").getOrElse(""),
        "--triplea", providers.gradleProperty("triplea").getOrElse("../triplea"),
    )
}

// A frozen copy of the runner and the engine it plays, so games can run from it while the source
// tree changes; see the Justfile's freeze and build=.
tasks.register<Sync>("freeze") {
    group = "ai benchmark"
    description = "Copies the runner's classpath to build/ai-benchmark/builds/<-PbuildName>/lib."
    val name = providers.gradleProperty("buildName")
    from(tasks.named("jar"))
    from(configurations.named("runtimeClasspath"))
    into(name.map { "$outRoot/builds/$it/lib" })
}

tasks.register<JavaExec>("listKata") {
    group = "ai benchmark"
    description = "Lists the kata found and their limits."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.triplea.ai.benchmark.runner.BenchmarkMain"
    args("--kata-dir", kataDir, "--list")
}
