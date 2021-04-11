import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    id("org.springframework.boot") version "2.4.4"
    id("io.spring.dependency-management") version "1.0.11.RELEASE"
    id("com.github.ben-manes.versions") version "0.38.0"
    kotlin("jvm") version "1.4.32"
    kotlin("kapt") version "1.4.32"
    kotlin("plugin.spring") version "1.4.32"
    kotlin("plugin.jpa") version "1.4.32"
}

object Versions {
    const val bouncycastle = "1.68"
    const val commonsio = "2.8.0"
    const val cbor = "0.01.01"
    const val coroutines = "1.4.3"
    const val googleTruth = "1.1.2"
    const val jackson = "2.12.3"
    const val joda = "2.10.10"
    const val json = "20210307"
    const val jsoup = "1.13.1"
    const val junit = "5.7.1"
    const val kotlinxIo = "0.1.16"
    const val liquibase = "4.3.3"
    const val mockk = "1.11.0"
    const val moshi = "1.12.0"
    const val okhttp = "4.9.1"
    const val retrofit = "2.9.0"
    const val springSecurity = "5.4.6"
    const val sshj = "0.31.0"
}

group = "com.swiftmako"
version = "3.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_11

repositories {
    mavenLocal()
    mavenCentral()
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

    implementation("org.springframework.security:spring-security-core:${Versions.springSecurity}")
    implementation("org.bouncycastle:bcprov-jdk15on:${Versions.bouncycastle}")

    implementation("org.liquibase:liquibase-core:${Versions.liquibase}")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    implementation("com.h2database:h2")

    // implementation("com.google.iot.cbor:cbor:${Versions.cbor}")
    implementation("org.json:json:${Versions.json}")
    compileOnly("com.google.errorprone:error_prone_annotations:2.2.0")
    compileOnly("org.checkerframework:checker-qual:2.5.6")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    implementation("com.muquit.libsodiumjna:libsodium-jna:1.1.0-IOG-SNAPSHOT")
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
                "-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-Xopt-in=kotlin.RequiresOptIn"
        )
        jvmTarget = "13"
        useIR = true
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

