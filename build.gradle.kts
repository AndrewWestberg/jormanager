import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import java.util.Locale
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version Versions.SPRING_BOOT
    id("io.spring.dependency-management") version Versions.SPRING_DEPENDENCY
    id("io.github.ben-manes.versions") version Versions.VERSIONS
    id("com.google.devtools.ksp") version Versions.KSP
    id("org.jlleitschuh.gradle.ktlint") version Versions.KTLINT_PLUGIN
    kotlin("jvm") version Versions.KOTLIN
    kotlin("plugin.spring") version Versions.KOTLIN
    kotlin("plugin.jpa") version Versions.KOTLIN
}

group = "com.swiftmako"
version = "11.4.0"
java.sourceCompatibility = JavaVersion.VERSION_21
java.targetCompatibility = JavaVersion.VERSION_21

repositories {
    mavenLocal()
    mavenCentral()
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")) {
            useVersion(Versions.COROUTINES)
            because("Keep coroutines modules aligned for Ktor socket shutdown compatibility")
        }
    }
}

configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name == "kotlin-compiler-embeddable") {
            useVersion("2.2.0")
            because("Prevent Spring/Kotlin BOM from overriding ktlint's bundled kotlin-compiler-embeddable with Kotlin 2.4 (KT-87267)")
        }
    }
}

ktlint {
    version.set(Versions.KTLINT)
}

dependencies {
    ksp("com.squareup.moshi:moshi-kotlin-codegen:${Versions.MOSHI}")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("tools.jackson.module:jackson-module-kotlin")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Versions.COROUTINES}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:${Versions.COROUTINES}")

    implementation("io.github.oshai:kotlin-logging:${Versions.KOTLIN_LOGGING}")

    implementation("com.hierynomus:sshj:${Versions.SSHJ}")
    implementation("org.jsoup:jsoup:${Versions.JSOUP}")
    implementation("com.squareup.okhttp3:okhttp:${Versions.OKHTTP}")
    implementation("com.squareup.okhttp3:logging-interceptor:${Versions.OKHTTP}")

    implementation("com.squareup.moshi:moshi-kotlin:${Versions.MOSHI}")
    implementation("com.squareup.retrofit2:converter-moshi:${Versions.RETROFIT}")
    implementation("joda-time:joda-time:${Versions.JODA_TIME}")

    implementation("io.ktor:ktor-network:${Versions.KTOR}")

    implementation("org.springframework.security:spring-security-core:${Versions.SPRING_SECURITY}")
    implementation("org.bouncycastle:bcprov-jdk18on:${Versions.BOUNCY_CASTLE}")

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
    implementation("com.bloxbean.cardano:cardano-client-lib:${Versions.CARDANO_CLIENT_LIB}")
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
    rejectVersionIf {
        isNonStable(candidate.version) && !isNonStable(currentVersion)
    }
    filterConfigurations =
        Spec<Configuration> {
            it.name.contains("ktlint", ignoreCase = true).not()
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

@CacheableTask
abstract class BuildVueTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val vueSources: ConfigurableFileCollection

        @get:OutputDirectory
        abstract val distDir: DirectoryProperty

        init {
            group = "build"
            description = "Builds the Vue.js frontend"
        }

        @TaskAction
        fun buildVue() {
            execOperations.exec {
                commandLine("/bin/bash", "./vue/deploy.sh")
            }
        }
    }

val buildVue =
    tasks.register<BuildVueTask>("buildVue") {
        vueSources.from(
            fileTree("vue/src"),
            fileTree("vue/public"),
            fileTree("vue/tests"),
            "vue/index.html",
            "vue/package.json",
            "vue/package-lock.json",
            "vue/tsconfig.json",
            "vue/vite.config.ts",
            "vue/deploy.sh",
        )
        distDir.set(layout.projectDirectory.dir("vue/dist"))
    }

@CacheableTask
abstract class TestVueTask
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val vueTestSources: ConfigurableFileCollection

        @get:OutputFile
        abstract val resultStamp: RegularFileProperty

        init {
            group = "verification"
            description = "Runs Vue.js frontend unit tests"
        }

        @TaskAction
        fun testVue() {
            execOperations.exec {
                commandLine("/bin/bash", "./vue/test.sh")
            }
            val stampFile = resultStamp.get().asFile
            stampFile.parentFile.mkdirs()
            stampFile.writeText("passed\n")
        }
    }

val testVue =
    tasks.register<TestVueTask>("testVue") {
        vueTestSources.from(
            fileTree("vue/src"),
            fileTree("vue/tests"),
            "vue/package.json",
            "vue/package-lock.json",
            "vue/tsconfig.json",
            "vue/vitest.config.ts",
            "vue/test.sh",
        )
        resultStamp.set(layout.buildDirectory.file("reports/vue-tests/result.stamp"))
    }

abstract class InstallGitHooksTask
    @Inject
    constructor(
        private val execOperations: ExecOperations
    ) : DefaultTask() {
        @get:Input
        abstract val ciEnvironment: Property<Boolean>

        @get:Input
        abstract val gitDirectoryPresent: Property<Boolean>

        init {
            group = "setup"
            description = "Configures git core.hooksPath to use repository .githooks"
        }

        @TaskAction
        fun installGitHooks() {
            if (ciEnvironment.get() || !gitDirectoryPresent.get()) {
                return
            }
            execOperations.exec {
                commandLine("git", "config", "--local", "core.hooksPath", ".githooks")
            }
        }
    }
tasks.register<InstallGitHooksTask>("installGitHooks") {
    ciEnvironment.set(
        providers
            .environmentVariable("CI")
            .map { it.isNotBlank() }
            .orElse(false)
    )
    gitDirectoryPresent.set(
        layout.projectDirectory
            .dir(".git")
            .asFile
            .exists()
    )
}

tasks.named("test") {
    dependsOn(testVue)
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<BootJar>("bootJar") {
    bootInf {
        into("classes/static") {
            from(buildVue.flatMap { it.distDir })
        }
    }
}

tasks.named<BootRun>("bootRun") {
    dependsOn(buildVue)
    systemProperty(
        "spring.web.resources.static-locations",
        "classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/,file:./vue/dist/",
    )
}

springBoot {
    buildInfo {
        excludes.set(setOf("time"))
    }
}
