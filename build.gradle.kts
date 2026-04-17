plugins {
    id("fabric-loom") version "1.14-SNAPSHOT"
    id("dev.kikugie.stonecutter")
}

val mcVersion = stonecutter.current.version

base {
    archivesName = properties["archives_base_name"] as String
    version = "${properties["mod_version"] as String}+$mcVersion"
    group = properties["maven_group"] as String
    // All version jars land in a single dir at the repo root.
    libsDirectory = rootProject.rootDir.resolve("build/libs").let { project.layout.projectDirectory.dir(it.absolutePath).asFile }.let { project.objects.directoryProperty().fileValue(it) }
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("net.fabricmc:yarn:${properties["yarn_mappings"] as String}:v2")
    modImplementation("net.fabricmc:fabric-loader:${properties["loader_version"] as String}")
    modImplementation("meteordevelopment:meteor-client:$mcVersion-SNAPSHOT")
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to mcVersion,
        )

        inputs.properties(propertyMap)
        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }

        // Sanitize manifest — no environment leaks.
        manifest {
            attributes(
                "Built-By" to "GriefKit",
                "Build-Jdk" to "21",
                "Specification-Title" to "GriefKit",
                "Specification-Version" to project.version.toString(),
                "Implementation-Title" to "GriefKit",
                "Implementation-Version" to project.version.toString()
            )
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
