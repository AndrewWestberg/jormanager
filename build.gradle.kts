import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import java.util.Locale
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    id("org.springframework.boot") version Versions.SPRING_BOOT
    id("io.spring.dependency-management") version Versions.SPRING_DEPENDENCY
    id("com.github.ben-manes.versions") version Versions.VERSIONS
    id("com.google.devtools.ksp") version Versions.KSP
    kotlin("jvm") version Versions.KOTLIN
    kotlin("plugin.spring") version Versions.KOTLIN
    kotlin("plugin.jpa") version Versions.KOTLIN
}

group = "com.swiftmako"
version = "10.0.0-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_21
java.targetCompatibility = JavaVersion.VERSION_21

repositories {
    mavenLocal()
    maven {
        name = "jitpack.io"
        url = uri("https://jitpack.io")
    }
    mavenCentral()
}

kotlin {
    kotlinDaemonJvmArgs =
        listOf(
            "-Dfile.encoding=UTF-8",
            "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        )
}

dependencies {
    ksp("com.squareup.moshi:moshi-kotlin-codegen:${Versions.MOSHI}")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${Versions.JACKSON}")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.COROUTINES}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.COROUTINES}")

    implementation("com.hierynomus:sshj:${Versions.SSHJ}")
    implementation("org.jsoup:jsoup:${Versions.JSOUP}")
    implementation("com.squareup.okhttp3:okhttp:${Versions.OKHTTP}")
    implementation("com.squareup.okhttp3:logging-interceptor:${Versions.OKHTTP}")

    implementation("com.squareup.moshi:moshi-kotlin:${Versions.MOSHI}")
    implementation("com.squareup.retrofit2:converter-moshi:${Versions.RETROFIT}")
    implementation("joda-time:joda-time:${Versions.JODA_TIME}")

    implementation("io.ktor:ktor-network:${Versions.KTOR}")

    implementation("org.springframework.security:spring-security-core:${Versions.SPRING_SECURITY}")
    implementation("org.bouncycastle:bcprov-jdk15on:${Versions.BOUNCY_CASTLE}")

    implementation("org.liquibase:liquibase-core:${Versions.LIQUIBASE}")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    implementation("org.postgresql:postgresql:${Versions.POSTGRESQL}")
    implementation("org.jetbrains.exposed:exposed-core:${Versions.EXPOSED}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${Versions.EXPOSED}")
    implementation("com.zaxxer:HikariCP:${Versions.HIKARI}")
    implementation("com.github.ben-manes.caffeine:caffeine:${Versions.CAFFEINE}")

    implementation("io.newm:com.google.iot.cbor:${Versions.CBOR}")
    implementation("org.json:json:${Versions.JSON}")
    compileOnly("com.google.errorprone:error_prone_annotations:${Versions.ERROR_PRONE}")
    compileOnly("org.checkerframework:checker-qual:${Versions.CHECKER_FRAMEWORK}")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    implementation("io.newm:com.muquit.libsodiumjna.libsodium-jna:${Versions.LIBSODIUM_JNA}")
    // implementation("com.squareup.jnagmp:jnagmp:3.0.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.mockk:mockk:${Versions.MOCKK}")
    testImplementation("com.google.truth:truth:${Versions.GOOGLE_TRUTH}")
    testImplementation("org.junit.jupiter:junit-jupiter:${Versions.JUNIT}")
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase(Locale.getDefault()).contains(it) }
    val regex = "^[0-9,.v-]+(-r)?$".toRegex()
    val isStable = stableKeyword || regex.matches(version)
    return isStable.not()
}

tasks.withType<DependencyUpdatesTask> {
    // Example 1: reject all non stable versions
    rejectVersionIf {
        isNonStable(candidate.version)
    }

    // Example 2: disallow release candidates as upgradable versions from stable versions
    rejectVersionIf {
        isNonStable(candidate.version) && !isNonStable(currentVersion)
    }

    // Example 3: using the full syntax
    resolutionStrategy {
        componentSelection {
            all {
                if (isNonStable(candidate.version) && !isNonStable(currentVersion)) {
                    reject("Release candidate")
                }
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs =
            listOf(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn",
            )
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.register("buildVue") {
    project.exec {
        commandLine = listOf("/bin/bash", "./vue/deploy.sh")
    }
}

// tasks.register("jvmOptsConfFile") {
//    doFirst {
//        File("${project.buildDir.absolutePath}/libs/jormanager-${version}.conf")
//                .writeText("JAVA_OPTS=-XX:+UnlockExperimentalVMOptions -XX:+UseZGC -Xmx1024m")
//    }
// }

tasks {
    springBoot {
        buildInfo()
    }
    bootJar {
        launchScript()
        dependsOn("buildVue" /*, "jvmOptsConfFile"*/)
    }
}
