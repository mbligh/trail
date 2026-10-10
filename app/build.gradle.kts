plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "net.pocketmap"
    compileSdk = 36
    defaultConfig { applicationId = "net.pocketmap"; minSdk = 26; targetSdk = 36; versionCode = 8; versionName = "1.7" }
    signingConfigs.getByName("debug") { storeFile = rootProject.file("development-signing.jks"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.osmdroid:osmdroid-android:6.1.20"); testImplementation("junit:junit:4.13.2") }
