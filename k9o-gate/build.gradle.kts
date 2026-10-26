plugins {
    id("com.android.library") version "8.7.0"
    kotlin("android") version "1.9.23"
    `maven-publish`
    signing
}

android {
    namespace = "dev.k9o.gate"
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

    testOptions {
        unitTests {
            // Allow Robolectric to find Android resources
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Network + serialisation
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.google.code.gson:gson:2.10.1")

    // Encrypted storage — EncryptedSharedPreferences + Android Keystore
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Unit tests (JVM + Robolectric for Android API shims)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
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
                artifactId = "gate"
                version = "1.3.0"
                pom {
                    name.set("K9OGate Android SDK")
                    description.set("Kilimanjaro Gate SDK for Android — EncryptedSharedPreferences token storage backed by Android Keystore")
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
