import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    id("org.springframework.boot") version "3.0.0"
    id("io.spring.dependency-management") version "1.1.0"
    id("com.github.ben-manes.versions") version "0.44.0"
    kotlin("jvm") version "1.7.22"
    kotlin("kapt") version "1.7.22"
    kotlin("plugin.spring") version "1.7.22"
    kotlin("plugin.jpa") version "1.7.22"
}

object Versions {
    const val bouncycastle = "1.70"
    const val caffeine = "3.1.2"
    const val commonsio = "2.8.0"
    const val cbor = "0.01.04-NEWM"
    const val checkerFramework = "3.28.0"
    const val coroutines = "1.6.4"
    const val ehcache = "3.9.9"
    const val errorprone = "2.16"
    const val exposed = "0.41.1"
    const val googleTruth = "1.1.3"
    const val hikari = "5.0.1"
    const val jackson = "2.14.1"
    const val joda = "2.12.2"
    const val json = "20220924"
    const val jsoup = "1.15.3"
    const val junit = "5.9.1"
    const val kotlinxIo = "0.1.16"
    const val ktor = "2.2.1"
    const val libSodiumJna = "1.1.0-NEWM"
    const val liquibase = "4.18.0"
    const val mockk = "1.13.3"
    const val moshi = "1.14.0"
    const val okhttp = "4.10.0"
    const val postgresql = "42.5.1"
    const val retrofit = "2.9.0"
    const val springSecurity = "6.0.0"
    const val sshj = "0.34.0"
}

group = "com.swiftmako"
version = "7.0.6-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

repositories {
    mavenLocal()
    maven {
        name = "jitpack.io"
        url = uri("https://jitpack.io")
    }
    mavenCentral()
}

kotlin {
    kotlinDaemonJvmArgs = listOf(
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
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}

dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${Versions.jackson}")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutines}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.coroutines}")
    implementation("org.jetbrains.kotlinx:kotlinx-io-jvm:${Versions.kotlinxIo}")

    implementation("com.hierynomus:sshj:${Versions.sshj}")
    implementation("org.jsoup:jsoup:${Versions.jsoup}")
    implementation("com.squareup.okhttp3:okhttp:${Versions.okhttp}")
    implementation("com.squareup.okhttp3:logging-interceptor:${Versions.okhttp}")

    implementation("com.squareup.moshi:moshi-kotlin:${Versions.moshi}")
    implementation("com.squareup.retrofit2:converter-moshi:${Versions.retrofit}")
    implementation("joda-time:joda-time:${Versions.joda}")

    implementation("io.ktor:ktor-network:${Versions.ktor}")

    implementation("org.springframework.security:spring-security-core:${Versions.springSecurity}")
    implementation("org.bouncycastle:bcprov-jdk15on:${Versions.bouncycastle}")

    implementation("org.liquibase:liquibase-core:${Versions.liquibase}")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    implementation("org.postgresql:postgresql:${Versions.postgresql}")
    implementation("org.jetbrains.exposed:exposed-core:${Versions.exposed}")
    implementation("org.jetbrains.exposed:exposed-jdbc:${Versions.exposed}")
    implementation("com.zaxxer:HikariCP:${Versions.hikari}")
    implementation("com.github.ben-manes.caffeine:caffeine:${Versions.caffeine}")

    implementation("io.newm:com.google.iot.cbor:${Versions.cbor}")
    implementation("org.json:json:${Versions.json}")
    compileOnly("com.google.errorprone:error_prone_annotations:${Versions.errorprone}")
    compileOnly("org.checkerframework:checker-qual:${Versions.checkerFramework}")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    implementation("io.newm:com.muquit.libsodiumjna.libsodium-jna:${Versions.libSodiumJna}")
    // implementation("com.squareup.jnagmp:jnagmp:3.0.0")


    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.mockk:mockk:${Versions.mockk}")
    testImplementation("com.google.truth:truth:${Versions.googleTruth}")
    testImplementation("org.junit.jupiter:junit-jupiter:${Versions.junit}")
}

fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.toUpperCase().contains(it) }
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
    kotlinOptions {
        freeCompilerArgs = listOf(
            "-Xjsr305=strict",
            "-opt-in=kotlin.RequiresOptIn",
        )
        jvmTarget = "17"
    }
}

tasks.register("buildVue") {
    project.exec {
        commandLine = listOf("/bin/bash", "./vue/deploy.sh")
    }
}

//tasks.register("jvmOptsConfFile") {
//    doFirst {
//        File("${project.buildDir.absolutePath}/libs/jormanager-${version}.conf")
//                .writeText("JAVA_OPTS=-XX:+UnlockExperimentalVMOptions -XX:+UseZGC -Xmx1024m")
//    }
//}

tasks {
    springBoot {
        buildInfo()
    }
    bootJar {
        launchScript()
        dependsOn("buildVue" /*, "jvmOptsConfFile"*/)
    }
}

