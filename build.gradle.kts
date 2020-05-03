import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "2.2.6.RELEASE"
    id("io.spring.dependency-management") version "1.0.9.RELEASE"
    id("com.github.ben-manes.versions") version "0.28.0"
    kotlin("jvm") version "1.3.72"
    kotlin("kapt") version "1.3.72"
    kotlin("plugin.spring") version "1.3.72"
}

object Versions {
    const val bouncycastle = "1.65"
    const val commonsMath = "3.6.1"
    const val coroutines = "1.3.4"
    const val jackson = "2.11.0"
    const val jaxb = "2.3.1"
    const val jjwt = "0.9.1"
    const val joda = "2.10.6"
    const val junit = "4.13"
    const val moshi = "1.9.2"
    const val okhttp = "4.6.0"
    const val retrofit = "2.8.1"
    const val sockjs = "1.1.2"
    const val springSecurity = "5.3.1.RELEASE"
    const val stomp = "2.3.3-1"
    const val webjarsBootstrap = "4.4.1-1"
    const val webjarsFontAwesome = "5.13.0"
    const val webjarsJquery = "3.5.0"
    const val webjarsLocator = "0.45"
}

group = "com.swiftmako"
version = "0.3.5-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_1_8

repositories {
    mavenCentral()
    jcenter()
}

dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-core:${Versions.springSecurity}")
    implementation("org.springframework.security:spring-security-web:${Versions.springSecurity}")
    implementation("org.springframework.security:spring-security-config:${Versions.springSecurity}")
    implementation("io.jsonwebtoken:jjwt:${Versions.jjwt}")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:${Versions.jackson}")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.coroutines}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.coroutines}")
    implementation("com.squareup.moshi:moshi-kotlin:${Versions.moshi}")
    implementation("com.squareup.retrofit2:converter-moshi:${Versions.retrofit}")
    implementation("com.squareup.okhttp3:okhttp:${Versions.okhttp}")
    implementation("com.squareup.okhttp3:logging-interceptor:${Versions.okhttp}")
    implementation("joda-time:joda-time:${Versions.joda}")
    implementation("org.apache.commons:commons-math3:${Versions.commonsMath}")
    implementation("javax.xml.bind:jaxb-api:${Versions.jaxb}")

    implementation("org.webjars:webjars-locator-core:${Versions.webjarsLocator}")
    implementation("org.webjars:sockjs-client:${Versions.sockjs}")
    implementation("org.webjars:stomp-websocket:${Versions.stomp}")
    implementation("org.webjars:bootstrap:${Versions.webjarsBootstrap}")
    implementation("org.webjars:jquery:${Versions.webjarsJquery}")
    implementation("org.webjars:font-awesome:${Versions.webjarsFontAwesome}")

    implementation("org.bouncycastle:bcprov-jdk15on:${Versions.bouncycastle}")
    implementation("org.bouncycastle:bcpg-jdk15on:${Versions.bouncycastle}")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("junit:junit:${Versions.junit}")
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
        freeCompilerArgs = listOf("-Xjsr305=strict")
        jvmTarget = JavaVersion.VERSION_1_8.toString()
    }
}

tasks {
    bootJar {
        launchScript()
    }
}