package com.swiftmako.jormanager.utils

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.scheduling.annotation.Scheduled
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Optional
import java.util.Properties
import java.util.stream.StreamSupport
import javax.annotation.PostConstruct


abstract class ReloadableProperties {

    @Autowired
    protected lateinit var environment: StandardEnvironment

    private lateinit var appConfigPropertySource: PropertySource<*>
    private lateinit var configPath: Path

    private var lastModTime = 0L


    @PostConstruct
    fun stopIfProblemsCreatingContext() {
        val propertySources = environment.propertySources
        val appConfigPsOp: Optional<PropertySource<*>> = StreamSupport.stream(propertySources.spliterator(), false)
                .filter { ps: PropertySource<*> -> ps.name.matches(Regex("^.*applicationConfig.*file:.*$")) }
                .findFirst()
        if (!appConfigPsOp.isPresent) {
            // this will stop context initialization
            // (i.e. kill the spring boot program before it initializes)
            throw RuntimeException("Unable to find property Source as file")
        }

        appConfigPropertySource = appConfigPsOp.get()

        val filename = appConfigPropertySource.name
                .replace("applicationConfig: [file:", "")
                .replace(Regex("\\]$"), "")
        configPath = Paths.get(filename)
    }

    @Scheduled(fixedRate = 2000)
    @Throws(IOException::class)
    fun reload() {
        val currentModTs: Long = Files.getLastModifiedTime(configPath).toMillis()
        if (currentModTs > lastModTime) {
            lastModTime = currentModTs
            val properties = Properties()
            Files.newInputStream(configPath).use { inputStream ->
                properties.load(inputStream)
            }
            environment.propertySources
                    .replace(
                            appConfigPropertySource.name,
                            PropertiesPropertySource(
                                    appConfigPropertySource.name,
                                    properties
                            )
                    )
            propertiesReloaded()
        }
    }

    protected abstract fun propertiesReloaded()
}