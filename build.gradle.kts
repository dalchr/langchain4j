plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-java-parameters")
    }
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.sessions)
    implementation(ktorLibs.server.thymeleaf)
    implementation(ktorLibs.server.websockets)
    implementation(libs.damirdenisTudor.ktorServerRabbitmq)
    implementation(libs.h2database.h2)
    implementation(libs.logback.classic)
    implementation(libs.langchain4j)
    implementation(libs.langchain4j.open.ai)
    implementation(libs.postgresql)
    implementation(libs.jbcrypt)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

tasks.register<Exec>("stopServer") {
    group = "server"
    description = "Stops the running Ktor server (inversion of the 'run' task)"
    commandLine = listOf("bash", "stop-server.sh")
}
