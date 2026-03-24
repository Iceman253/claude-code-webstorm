plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.claudecode.webstorm"
version = "1.1.1"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

intellij {
    version.set("2024.1.7")
    type.set("IU")
    plugins.set(listOf())
    downloadSources.set(false)
    updateSinceUntilBuild.set(false)
}

tasks {
    wrapper {
        gradleVersion = "8.9"
    }

    patchPluginXml {
        sinceBuild.set("241")
    }

    buildSearchableOptions {
        enabled = false
    }

    test {
        useJUnitPlatform()
    }
}
