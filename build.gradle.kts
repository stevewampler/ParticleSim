plugins {
    kotlin("jvm") version "2.0.20"
    application
}

group = "particlesim"
version = "0.1.0-SNAPSHOT"

// Arrow's off-heap memory allocator (§9.2's recording format) reaches into java.nio via
// reflection; JDK 16+'s strong encapsulation blocks that without these opens. Needed on every
// JVM that links arrow-memory-netty: tests, and (once the recorder is wired into a demo) the
// run/JavaExec tasks below.
val arrowAddOpens = listOf(
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
)

repositories {
    mavenCentral()
}

dependencies {
    // Phase 3's debug renderer (§9.1, §10.2): a minimal one-way WebSocket state stream.
    // A single small jar rather than a full framework (Ktor etc.) — all Phase 3 needs is a
    // WebSocketServer subclass to broadcast on; Phase 8's bidirectional upgrade reuses the
    // same class via its onMessage callback, no re-architecture.
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    // Phase 7's YAML front-end (§4.2): only used to parse YAML text into generic
    // Map/List/scalar structures — all schema validation and binding into the simulation
    // model is hand-written (particlesim.yaml.YamlLoader), the same "own the sandbox
    // boundary, don't lean on a general framework" choice already made for the expression
    // parser (§4.1) rather than a data-binding library like Jackson.
    implementation("org.yaml:snakeyaml:2.2")
    // Phase 8's recording format (§9.2): Arrow IPC File format, chosen in requirements.md over
    // a custom binary format or Parquet for its footer-based random access and off-the-shelf
    // columnar per-frame writer. Spiking this dependency before designing the recorder around
    // it, since arrow-memory's off-heap allocator needs `--add-opens` on JDK 16+.
    implementation("org.apache.arrow:arrow-vector:18.1.0")
    implementation("org.apache.arrow:arrow-memory-netty:18.1.0")
    testImplementation(kotlin("test"))
}

application {
    // `./gradlew run` — the spring-chain demo. `./gradlew runFlagDemo` below runs §7.3's
    // flag instead; the `application` plugin only wires up one default `run` task, so the
    // second demo gets its own JavaExec task rather than a way to swap `run`'s target.
    mainClass.set("particlesim.debug.DebugRendererDemoKt")
}

tasks.register<JavaExec>("runFlagDemo") {
    group = "application"
    description = "Runs §7.3's flag worked example through the Phase 3 debug renderer."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("particlesim.debug.FlagDebugDemoKt")
}

tasks.register<JavaExec>("runBallBounceDemo") {
    group = "application"
    description = "Runs §12.6's ball-bounce worked example through the Phase 3 debug renderer."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("particlesim.debug.BallBounceDebugDemoKt")
}

tasks.register<JavaExec>("runSparksDemo") {
    group = "application"
    description = "Runs §14's spark-fountain worked example through the Phase 3 debug renderer."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("particlesim.debug.SparksDebugDemoKt")
}

tasks.register<JavaExec>("runDragDemo") {
    group = "application"
    description = "Runs §9.4's interactive drag worked example (click-and-drag a spring-chain particle)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("particlesim.debug.DragDebugDemoKt")
}

tasks.register<JavaExec>("runMultiShapeDemo") {
    group = "application"
    description = "Runs §4.5's shape-library worked example (two flags and a ball-bounce sharing one scene)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("particlesim.debug.MultiShapeDebugDemoKt")
}

tasks.register<JavaExec>("runTrampolineDemo") {
    group = "application"
    description = "Runs §12.8's trampoline worked example (a ball bouncing off a deforming pinned-rim surface)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("particlesim.debug.TrampolineDebugDemoKt")
}

tasks.register<JavaExec>("runParticleCollisionDemo") {
    group = "application"
    description = "Runs §12.4/§12.5's particle-vs-particle collision worked example (a cluster of balls piling up on a floor)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("particlesim.debug.ParticleCollisionDebugDemoKt")
}

kotlin {
    // Kotlin 2.0.20 doesn't support a JVM 23 bytecode target yet. Toolchain 23 + an
    // explicit JVM_22 compilerOptions target used to work around this, but Gradle's
    // auto-generated `compileJava` task still targets the toolchain version, so it and
    // `compileKotlin` disagreed (23 vs 22) the moment there was Kotlin source to compile.
    // Pinning the toolchain itself to 22 keeps both tasks consistent.
    jvmToolchain(22)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(arrowAddOpens)
}

// Every JavaExec run task (the `application` plugin's `run`, plus the demo tasks above) links
// the same arrow-memory-netty dependency the tests do, so they need the same opens.
tasks.withType<JavaExec>().configureEach {
    jvmArgs(arrowAddOpens)
}
