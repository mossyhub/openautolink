import java.io.File
import java.nio.file.Files

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

val oalBuilderUnsigned = providers.gradleProperty("oalBuilderUnsigned").orNull == "true"

// Explicit owner-authorized internal builds only. Never copy credentials into source/generated dirs.
val oalCarPlayMfiAssetsDir = providers.gradleProperty("oalCarPlayMfiAssetsDir").orNull?.let { path ->
    require(path.isNotBlank()) { "oalCarPlayMfiAssetsDir must name an external asset root" }
    val root = file(path).canonicalFile
    require(!root.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
        "MFi assets must be outside the repository"
    }
    val identity = File(root, "offline-mfi")
    val names = setOf("identity.pk8", "certificate.p7b")
    require(root.isDirectory && root.list()?.toSet() == setOf("offline-mfi") &&
        identity.isDirectory && identity.list()?.toSet() == names) {
        "MFi asset root must contain exactly offline-mfi/identity.pk8 and offline-mfi/certificate.p7b"
    }
    require(!Files.isSymbolicLink(identity.toPath()) && names.all { name ->
        val input = File(identity, name)
        input.isFile && !Files.isSymbolicLink(input.toPath()) && input.length() in 1L..16384L
    }) { "MFi assets must be complete bounded regular files, not links" }
    root
}

android {
    namespace = "com.openautolink.app"
    // Direct external input; APK/AAB assets are extractable, not encrypted secret storage.
    oalCarPlayMfiAssetsDir?.let { sourceSets.getByName("main").assets.srcDir(it) }
    compileSdk = 36

    defaultConfig {
        applicationId = findProperty("appId") as? String ?: "com.openautolink.app"
        minSdk = 32
        targetSdk = 36
        versionCode = (findProperty("oalVersionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (findProperty("oalVersionName") as? String) ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("secrets/upload-key.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        create("tabletDebug") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (!oalBuilderUnsigned) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // NDK build for aasdk JNI
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "28.2.13676358"

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // CarAppActivity is from androidx.car.app and does extend Activity,
        // but lint can't resolve it through the library's class hierarchy.
        disable += "Instantiatable"
    }
}

androidComponents {
    onVariants(selector().all()) {
        it.packaging.resources.excludes.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(project(":carplay-lab"))
    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Navigation
    implementation(libs.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Car App Library (cluster navigation)
    implementation(libs.car.app)
    implementation(libs.car.app.automotive)

    // MediaSession for AAOS integration
    implementation(libs.media)

    // Protobuf (AA wire protocol for direct mode)
    implementation(libs.protobuf.javalite)

    // Conscrypt (TLS provider for AA SSL handshake)
    implementation(libs.conscrypt.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
