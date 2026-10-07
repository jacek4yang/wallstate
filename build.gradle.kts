import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    kotlin("jvm") version "2.2.21"
}

// wallstate is a device-side CLI executed through app_process. It is compiled
// against the public Android SDK (android.jar) but never packaged as an APK;
// the build dexes the compiled classes into a single classes.dex inside
// wallstate.jar, which app_process loads from /data/local/tmp.
//
// Prerequisite: Android SDK platform android-36 (android.jar).
// Resolved from (in order): local.properties sdk.dir, ANDROID_HOME, ANDROID_SDK_ROOT.

repositories {
    mavenCentral()
    google() // com.android.tools:r8 (D8 dexer)
}

val r8 by configurations.creating

val androidJar: File by lazy {
    val sdkDirs = mutableListOf<File>()
    val localProps = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (localProps.isFile) {
        val prop = Properties()
        localProps.inputStream().use { stream -> prop.load(stream) }
        val d = prop.getProperty("sdk.dir")
        if (d != null) sdkDirs.add(File(d))
    }
    System.getenv("ANDROID_HOME")?.let { sdkDirs.add(File(it)) }
    System.getenv("ANDROID_SDK_ROOT")?.let { sdkDirs.add(File(it)) }
    for (sdk in sdkDirs) {
        val jar = File(sdk, "platforms/android-36/android.jar")
        if (jar.isFile) return@lazy jar
    }
    throw GradleException(
        "Android SDK platform android-36 not found (looked in local.properties sdk.dir, " +
            "ANDROID_HOME, ANDROID_SDK_ROOT). Install it, e.g.:\n" +
            "  sdkmanager \"platforms;android-36\"\n" +
            "or download https://dl.google.com/android/repository/platform-36_r02.zip and " +
            "unpack android-36/ into <sdk>/platforms/android-36/."
    )
}

dependencies {
    // Public Android SDK types (android.jar); provided by the platform at runtime.
    compileOnly(files(androidJar))
    // Provided by the Android framework at runtime (org.json is part of the boot classpath);
    // needed on the JVM test classpath too.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")

    r8("com.android.tools:r8:9.5.22")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
        showExceptions = true
    }
}

// D8-compile the project classes plus runtime dependencies (kotlin-stdlib)
// into classes.dex under build/dex.
val dexDir = layout.buildDirectory.dir("dex")
val dex = tasks.register<JavaExec>("dex") {
    dependsOn(tasks.compileKotlin)
    val runtimeClasspath = configurations.runtimeClasspath.get()
    inputs.files(tasks.compileKotlin.map { it.outputs.files }, runtimeClasspath)
    outputs.dir(dexDir)

    classpath = r8
    mainClass.set("com.android.tools.r8.D8")

    doFirst {
        dexDir.get().asFile.deleteRecursively()
        dexDir.get().asFile.mkdirs()
    }

    argumentProviders.add {
        buildList {
            add("--release")
            add("--min-api")
            add("26")
            add("--lib")
            add(androidJar.absolutePath)
            add("--output")
            add(dexDir.get().asFile.absolutePath)
            tasks.compileKotlin.get().outputs.files
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "class" } }
                .forEach { add(it.absolutePath) }
            runtimeClasspath.files.forEach { add(it.absolutePath) }
        }
    }
}

// The deliverable: a JAR whose root contains classes.dex so that
//   CLASSPATH=/data/local/tmp/wallstate.jar app_process /system/bin <mainclass>
// can execute it.
val wallstateJar = tasks.register<Zip>("wallstateJar") {
    group = "build"
    description = "Builds wallstate.jar (classes.dex for app_process)."
    dependsOn(dex)
    archiveFileName = "wallstate.jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
    from(dex.map { it.outputs })
}

tasks.build {
    dependsOn(wallstateJar)
}

tasks.clean {
    delete(layout.buildDirectory.dir("dex"))
}
