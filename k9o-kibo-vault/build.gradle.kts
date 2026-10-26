plugins {
    id("com.android.library") version "8.7.0"
    kotlin("android") version "1.9.23"
    `maven-publish`
    signing
}

android {
    namespace = "dev.k9o.kibo.vault"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        targetSdk = 36
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Network + serialisation — same stack as dev.k9o:gate (OkHttp + Gson); this module has no native
    // FFI dependency (unlike dev.k9o:kibo-android's uniffi/JNA binding), so it builds standalone from a fresh
    // checkout with no CI-injected artifacts.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

afterEvaluate {
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
                from(components["release"])
                groupId = "dev.k9o"
                artifactId = "kibo-vault"
                version = "1.3.0"
                pom {
                    name.set("K9OKiboVault Android SDK")
                    description.set(
                        "Kibo vault-secrets REST surface for Android — list/get/create/revealChallenge/" +
                            "reveal/update/rotate/move/delete over the gateway's secrets API " +
                            "(services/gateway/src/routes/vault-secrets.ts). Companion to dev.k9o:kibo-android " +
                            "(ZK encrypt/decrypt) and dev.k9o:gate (auth)."
                    )
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
}
