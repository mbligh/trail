plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "net.pocketmap"
    compileSdk = 36
    defaultConfig { applicationId = "net.pocketmap"; minSdk = 26; targetSdk = 36; versionCode = 9; versionName = "1.8" }
    signingConfigs.getByName("debug") { storeFile = rootProject.file("development-signing.jks"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.osmdroid:osmdroid-android:6.1.20"); testImplementation("junit:junit:4.13.2") }

// Bundle the repository-root configuration on every build; do not maintain a second copy.
val bundleBasemaps by tasks.registering(Sync::class) {
    from(rootProject.file("basemaps.json"))
    into(layout.buildDirectory.dir("generated/basemapAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/basemapAssets"))
tasks.named("preBuild").configure { dependsOn(bundleBasemaps) }
