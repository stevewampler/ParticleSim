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
    // `./gradlew run` — the spring-chain demo, the original Phase 3 debug renderer. Every
    // other worked example (flag, ballBounce, trampoline, sparks, drag, particleCollision,
    // spatialGrid, multiShape) used to each get its own JavaExec task and standalone `main()`,
    // but that meant every new interactive/control-message feature had to be wired twice —
    // once for the scene-library `*Scene` classes below and once for each standalone demo's own
    // hand-rolled dispatch. Superseded by `runSceneLibraryDemo`'s scene picker (§9.6), which
    // reaches every one of those scenarios losslessly; the standalone demos and their tasks were
    // removed once that was confirmed. The `application` plugin only wires up one default `run`
    // task, so `runSceneLibraryDemo` still gets its own JavaExec task rather than a way to swap
    // `run`'s target.
    mainClass.set("particlesim.debug.DebugRendererDemoKt")
}

tasks.register<JavaExec>("runSceneLibraryDemo") {
    group = "application"
    description = "Runs §9.6's scene library (flag/ballBounce/trampoline/sparks/drag/particleCollision/spatialGrid/multiShape, " +
        "switchable via load_scene without reconnecting). Pass --args=\"<sceneName>\" to start on a scene other than " +
        "the flag default, e.g. ./gradlew runSceneLibraryDemo --args=\"trampoline\"."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("particlesim.debug.SceneLibraryDebugDemoKt")
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
