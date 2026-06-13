plugins {
    java
    application
}

group = "pablog"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.formdev:flatlaf:3.7.1")
    implementation("tools.jackson.core:jackson-databind:3.2.0")
    implementation("com.github.weisj:jsvg:2.1.0")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("pablog.selextrace.launcher.Main")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "pablog.selextrace.launcher.Main"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

