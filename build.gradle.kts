plugins {
    kotlin("jvm") version "2.0.20"
}

group = "particlesim"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
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
