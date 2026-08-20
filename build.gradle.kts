plugins {
    kotlin("jvm") version "2.0.20"
    application
}

group = "particlesim"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Phase 3's debug renderer (§9.1, §10.2): a minimal one-way WebSocket state stream.
    // A single small jar rather than a full framework (Ktor etc.) — all Phase 3 needs is a
    // WebSocketServer subclass to broadcast on; Phase 8's bidirectional upgrade reuses the
    // same class via its onMessage callback, no re-architecture.
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
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
}
