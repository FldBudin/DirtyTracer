plugins {
    alias(libs.plugins.android.application)
}

android {
    enableKotlin = false
    namespace = "io.fldbudin.dirtytracer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.fldbudin.dirtytracer"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            vcsInfo.include = false
            signingConfig = signingConfigs["debug"]
            optimization {
                enable = true
                keepRules {
                    ignoreFromAllExternalDependencies = true
                    includeDefault = false
                }
            }
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "**"
        }
    }
    lint {
        checkReleaseBuilds = false
    }
    dependenciesInfo {
        includeInApk = false
    }
}
