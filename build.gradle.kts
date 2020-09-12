import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "2.3.3.RELEASE"
    id("io.spring.dependency-management") version "1.0.10.RELEASE"
    id("com.github.ben-manes.versions") version "0.31.0"
    kotlin("jvm") version "1.4.10"
    kotlin("kapt") version "1.4.10"
    kotlin("plugin.spring") version "1.4.10"
    kotlin("plugin.jpa") version "1.4.10"
}

object Versions {
    const val bouncycastle = "1.66"
    const val commonsio = "2.8.0"
    const val coroutines = "1.3.9"
    const val googleTruth = "1.0.1"
    const val jackson = "2.11.2"
    const val joda = "2.10.6"
    const val jsoup = "1.13.1"
    const val kotlinxIo = "0.1.16"
    const val liquibase = "3.10.2"
    const val mockk = "1.10.0"
    const val moshi = "1.10.0"
    const val okhttp = "4.8.1"
    const val retrofit = "2.9.0"
    const val springSecurity = "5.4.0"
    const val sshj = "0.30.0"
}

group = "com.swiftmako"
version = "1.0.5-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_11

repositories {
    mavenCentral()
    jcenter()
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

    implementation("commons-io:commons-io:${Versions.commonsio}")

    implementation("com.squareup.moshi:moshi-kotlin:${Versions.moshi}")
    implementation("com.squareup.retrofit2:converter-moshi:${Versions.retrofit}")
    implementation("joda-time:joda-time:${Versions.joda}")

    implementation("org.springframework.security:spring-security-core:${Versions.springSecurity}")
    implementation("org.bouncycastle:bcprov-jdk15on:${Versions.bouncycastle}")

    implementation("org.liquibase:liquibase-core:${Versions.liquibase}")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    implementation("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.mockk:mockk:${Versions.mockk}")
    testImplementation("com.google.truth:truth:${Versions.googleTruth}")
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
                "-Xuse-experimental=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
        jvmTarget = "13"
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

