plugins {
    kotlin("jvm") version "1.9.23"
    `maven-publish`
    signing
}

// Compile with whatever JDK is installed (17 or newer) and emit Java 17 bytecode.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

publishing {
    // The release job zips this directory and uploads it to the Central Portal.
    repositories {
        maven {
            name = "bundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
    }
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            groupId = "dev.k9o"
            artifactId = "core-kotlin"
            version = "1.3.0"
            pom {
                name.set("K9O Core Kotlin SDK")
                description.set("Kilimanjaro project-plane core client for Kotlin/JVM and Android")
                url.set("https://kilimanjaro.dev")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("Kilimanjaro Engineering")
                        email.set("engineering@kilimanjaro.dev")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/k9o-dev/sdk-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/k9o-dev/sdk-kotlin.git")
                    url.set("https://github.com/k9o-dev/sdk-kotlin")
                }
            }
        }
    }
}

// Signed only when a key is supplied (CI release job); local builds and tests are unaffected.
signing {
    val key = providers.environmentVariable("K9O_SIGNING_KEY").orNull
    val passphrase = providers.environmentVariable("K9O_SIGNING_PASSPHRASE").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, passphrase)
        sign(publishing.publications)
    }
}
