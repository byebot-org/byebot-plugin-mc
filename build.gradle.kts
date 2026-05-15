plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.ronaldzav"
version = "26.5"
description = "Professional open-source antibot for Velocity"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.20.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.google.zxing:core:3.5.3")
}

tasks {
    processResources {
        filesMatching(listOf("plugin.properties", "velocity-plugin.json")) {
            expand("version" to project.version)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("com.google.gson", "dev.ronaldzav.byebot.libs.gson")
        relocate("org.yaml.snakeyaml", "dev.ronaldzav.byebot.libs.snakeyaml")
        relocate("com.google.zxing", "dev.ronaldzav.byebot.libs.zxing")
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
