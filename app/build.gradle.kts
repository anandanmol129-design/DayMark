plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.anmol.daymark"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.anmol.daymark"
        minSdk = 31
        targetSdk = 35
        versionCode = 31
        versionName = "1.23.0"
    }

    buildTypes {
        release {
            // R8 + resource shrinking make Compose far faster than the debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key as the debug builds already installed, so updates keep their data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.13.0-alpha01")
    implementation("androidx.compose.ui:ui-tooling-preview:1.13.0-alpha01")
    implementation("androidx.compose.foundation:foundation:1.13.0-alpha01")
    implementation("androidx.compose.material3:material3:1.5.0-alpha26")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.13.0-alpha01")
}

/*
 * Widget styles. Home-screen widgets are drawn from XML, and each widget can pick Light or Dark and an
 * accent colour, so every widget layout and every drawable carrying the accent needs a copy per style.
 * Rather than keeping those by hand, this task writes them at build time from the dark originals in
 * src/main/res ("<name>_<dark|light>_<accent>"), plus WidgetRes.java to look them up. Edit only the originals.
 *
 * The accent order here must match WidgetAccent in WidgetLook.kt.
 */
abstract class WidgetStyleResTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDir: DirectoryProperty

    @get:OutputDirectory
    abstract val resOut: DirectoryProperty

    @get:OutputDirectory
    abstract val javaOut: DirectoryProperty

    /** fill: buttons and today; ink: accent text; onFill: drawn on the fill; container: the header band. */
    private class Accent(val fill: String, val ink: String, val onFill: String, val container: String)

    private val accentNames = listOf("coral", "blue", "sunflower", "mint", "grape")
    private val lightAccents = listOf(
        Accent("FFFF5A5F", "FFD7263D", "FFFFFFFF", "FFFFE2E0"),
        Accent("FF3D5AFE", "FF2F4AE8", "FFFFFFFF", "FFE1E6FF"),
        Accent("FFFFC23C", "FF9A6200", "FF2B1D00", "FFFFF0C4"),
        Accent("FF1DB97A", "FF0B8A56", "FFFFFFFF", "FFD3F5E5"),
        Accent("FF7C4DFF", "FF6236E0", "FFFFFFFF", "FFEAE2FF")
    )
    // Dark accents are softer so they glow on navy; blue is the original dark widget colour.
    private val darkAccents = listOf(
        Accent("FFFF8A80", "FFFF8A80", "FF161B2E", "FF3A1F2A"),
        Accent("FF9DB8FF", "FF9DB8FF", "FF161B2E", "FF1E2440"),
        Accent("FFFFD36B", "FFFFD36B", "FF161B2E", "FF35301F"),
        Accent("FF6FE0B0", "FF6FE0B0", "FF161B2E", "FF17352C"),
        Accent("FFB49CFF", "FFB49CFF", "FF161B2E", "FF2A2346")
    )

    // Dark widget colour -> light one (cream card, warm near-black ink).
    private val toLight = mapOf(
        "FFF1F1F2" to "FF221A16", // text
        "FFC4C9DC" to "FF4A3F38", // secondary text
        "FF8A90A6" to "FF75685F", // muted
        "FFA9AEC0" to "FF8A7C72",
        "FF5F667D" to "FFB8ABA1", // days outside the month
        "26FFFFFF" to "1A2A1F1A", // dividers
        "33FFFFFF" to "262A1F1A", // outlines
        "F2161B2E" to "F7FFF8F1"  // card background
    )
    private val ACCENT = "FF9DB8FF"
    private val ON_ACCENT = "FF161B2E"
    private val HEADER = "FF1E2440"
    private val hex = Regex("#([0-9A-Fa-f]{8})")

    private fun recolour(text: String, light: Boolean, accent: Accent, accentAsInk: Boolean) = hex.replace(text) { m ->
        val c = m.groupValues[1].uppercase()
        "#" + when (c) {
            ACCENT -> if (accentAsInk) accent.ink else accent.fill
            ON_ACCENT -> accent.onFill
            HEADER -> accent.container
            else -> if (light) toLight[c] ?: c else c
        }
    }

    private fun styles() = listOf(false, true).flatMap { light ->
        (if (light) lightAccents else darkAccents).mapIndexed { i, accent -> Triple(light, i, accent) }
    }

    private fun suffix(light: Boolean, accent: Int) = "_" + (if (light) "light" else "dark") + "_" + accentNames[accent]

    @TaskAction
    fun generate() {
        val res = resDir.get().asFile
        val out = resOut.get().asFile
        out.deleteRecursively()
        File(out, "drawable").mkdirs()
        File(out, "layout").mkdirs()

        // Drawables carrying the accent get a copy per style; the rest only need a light copy.
        val styled = mutableListOf<String>()
        val lightOnly = mutableListOf<String>()
        File(res, "drawable").listFiles { f -> f.name.matches(Regex("(widget_|ic_widget_).*\\.xml")) }!!.sortedBy { it.name }.forEach { f ->
            val name = f.nameWithoutExtension
            val text = f.readText()
            val used = hex.findAll(text).map { it.groupValues[1].uppercase() }.toSet()
            when {
                used.any { it == ACCENT || it == ON_ACCENT || it == HEADER } -> {
                    styled += name
                    styles().forEach { (light, i, accent) ->
                        File(out, "drawable/$name${suffix(light, i)}.xml").writeText(recolour(text, light, accent, accentAsInk = name == "ic_widget_chevron"))
                    }
                }
                used.any { it in toLight } -> {
                    lightOnly += name
                    File(out, "drawable/${name}_light.xml").writeText(hex.replace(text) { m -> "#" + (toLight[m.groupValues[1].uppercase()] ?: m.groupValues[1]) })
                }
            }
        }

        // Every widget layout (not the static picker previews), pointing at its style's drawables.
        val layouts = File(res, "layout").listFiles { f -> f.name.startsWith("widget_") && !f.name.startsWith("widget_preview") }!!.sortedBy { it.name }.map { it.nameWithoutExtension }
        layouts.forEach { name ->
            val original = File(res, "layout/$name.xml").readText()
            styles().forEach { (light, i, accent) ->
                val text = Regex("@drawable/([a-z0-9_]+)").replace(recolour(original, light, accent, accentAsInk = true)) { m ->
                    val d = m.groupValues[1]
                    when {
                        d in styled -> "@drawable/$d${suffix(light, i)}"
                        d in lightOnly && light -> "@drawable/${d}_light"
                        else -> m.value
                    }
                }
                File(out, "layout/$name${suffix(light, i)}.xml").writeText(text)
            }
        }

        // The lookup table: original id -> its 10 styled ids (dark accents, then light accents).
        val java = javaOut.get().asFile
        java.deleteRecursively()
        val dir = File(java, "com/anmol/daymark").apply { mkdirs() }
        fun table(type: String, names: List<String>) = names.joinToString("\n") { n ->
            val ids = styles().joinToString(", ") { (light, i, _) -> "R.$type.$n${suffix(light, i)}" }
            "        $type.put(R.$type.$n, new int[] { $ids });"
        }
        File(dir, "WidgetRes.java").writeText("""
package com.anmol.daymark;

import java.util.HashMap;

/** Generated by generateWidgetStyleRes in app/build.gradle.kts: the styled copies of each widget resource. */
final class WidgetRes {
    private static final HashMap<Integer, int[]> layout = new HashMap<>();
    private static final HashMap<Integer, int[]> drawable = new HashMap<>();
    private static final HashMap<Integer, Integer> lightDrawable = new HashMap<>();
    static {
${table("layout", layouts)}
${table("drawable", styled)}
${lightOnly.joinToString("\n") { "        lightDrawable.put(R.drawable.$it, R.drawable.${it}_light);" }}
    }

    private WidgetRes() {}

    private static int index(boolean light, int accent) { return (light ? ${accentNames.size} : 0) + accent; }

    /** The layout to inflate for the original [dark] layout in this style. */
    static int layout(int dark, boolean light, int accent) {
        int[] ids = layout.get(dark);
        return ids == null ? dark : ids[index(light, accent)];
    }

    /** The drawable to use for the original [dark] drawable in this style. */
    static int drawable(int dark, boolean light, int accent) {
        int[] ids = drawable.get(dark);
        if (ids != null) return ids[index(light, accent)];
        Integer plain = light ? lightDrawable.get(dark) : null;
        return plain == null ? dark : plain;
    }
}
""".trimStart())
    }
}

val widgetStyleRes = tasks.register<WidgetStyleResTask>("generateWidgetStyleRes") {
    resDir.set(layout.projectDirectory.dir("src/main/res"))
    resOut.set(layout.buildDirectory.dir("generated/widgetStyles/res"))
    javaOut.set(layout.buildDirectory.dir("generated/widgetStyles/java"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(widgetStyleRes, WidgetStyleResTask::resOut)
        variant.sources.java?.addGeneratedSourceDirectory(widgetStyleRes, WidgetStyleResTask::javaOut)
    }
}
