# Black Cloud Binaural — Reference Bundle for Android Studio Setup

**State of this bundle:** everything here reflects Phase 0, 1, and 2 fixes as reviewed and confirmed in our sessions. **Phase 3 (audio correctness: sample clamping, stop/write race, silent audio-focus failure) and Phase 4 (WAV export Hemi-Sync parity, volume control) have NOT been applied yet** — `BinauralAudioService.kt` and `WavExporter.kt` below are still in their original, pre-Phase-3/4 state, carrying the known issues documented in the MVP plan. This bundle gets you to a buildable, Hemi-Sync-selectable, non-crashing-on-migration app — not a finished MVP.

Use this to populate a fresh Android Studio project (package `com.blackcloudgroup.binaural`, min SDK 26). File paths are given relative to the module root.

---

## `app/src/main/res/xml/file_paths.xml`
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="wav_exports" path="." />
</paths>
```

---

## `app/src/main/AndroidManifest.xml`
*(Phase 0 fix applied — correct `android:name=` attributes throughout, per the verified Health Connect permission strings.)*
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.blackcloudgroup.binaural">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <!-- Health Connect Permissions (verified strings) -->
    <uses-permission android:name="android.permission.health.READ_EXERCISE" />
    <uses-permission android:name="android.permission.health.WRITE_MINDFULNESS" />

    <!-- Package visibility query for Health Connect provider on Android 11+ -->
    <queries>
        <package android:name="com.google.android.apps.healthdata" />
    </queries>

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="Black Cloud Binaural"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.BinauralApp">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- Health Connect Permissions Rationale Intent Filter -->
            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" />
            </intent-filter>
        </activity>

        <service
            android:name=".BinauralAudioService"
            android:foregroundServiceType="mediaPlayback"
            android:exported="false" />

        <!-- FileProvider for Sharing Exported WAV Audio Files -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>

</manifest>
```

---

## `app/build.gradle.kts`
*(⚠️ `kotlinCompilerExtensionVersion`/`composeOptions` pattern flagged earlier as possibly pre-Kotlin-2.0 style — verify against current Compose compiler plugin docs before treating as final. `androidx.health.connect:connect-client:1.1.0-alpha11` also flagged as due for a version bump — check current release notes given mindfulness API churn.)*
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.blackcloudgroup.binaural"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blackcloudgroup.binaural"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose UI
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room Database (KSP)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Health Connect Client
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")

    // MediaSession
    implementation("androidx.media:media:1.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
```

---

## `app/src/main/java/com/blackcloudgroup/binaural/data/PresetEntity.kt`
*(Phase 2 final — `soundMode: String` replaces `isIsochronic: Boolean`. No `@ColumnInfo` annotations, confirmed clean.)*
```kotlin
package com.blackcloudgroup.binaural.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startBeatHz: Double,
    val targetBeatHz: Double,
    val carrierHz: Double,
    val durationMinutes: Int,
    val soundMode: String = "HEMI_SYNC",
    val enablePinkNoise: Boolean = true
)
```

---

## `app/src/main/java/com/blackcloudgroup/binaural/data/PresetDao.kt`
*(Unchanged from original — no field references, no changes needed.)*
```kotlin
package com.blackcloudgroup.binaural.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY title ASC")
    fun getAllPresets(): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: PresetEntity)

    @Delete
    suspend fun deletePreset(preset: PresetEntity)
}
```

---

## `app/src/main/java/com/blackcloudgroup/binaural/data/AppDatabase.kt`
*(Phase 2 final — version 2, full table-rebuild `MIGRATION_1_2`, no `DEFAULT` clauses, `exportSchema = true`.)*
```kotlin
package com.blackcloudgroup.binaural.data

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PresetEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun presetDao(): PresetDao

    private class DatabaseCallback : RoomDatabase.Callback() {

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            populateDefaultPresets(db)
        }

        private fun populateDefaultPresets(db: SupportSQLiteDatabase) {
            val insertSql = """
                INSERT INTO presets (title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, soundMode, enablePinkNoise)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            db.beginTransaction()
            try {
                val defaults = listOf(
                    arrayOf("Theta Deep Meditation", 6.0, 6.0, 136.1, 20, "HEMI_SYNC", 1),
                    arrayOf("Schumann Earth Resonance", 7.83, 7.83, 144.0, 30, "HEMI_SYNC", 1),
                    arrayOf("Deep Sleep Ramp (Alpha → Delta)", 10.0, 2.5, 174.0, 45, "HEMI_SYNC", 1),
                    arrayOf("Lucid Dreaming Gateway", 4.0, 4.0, 210.0, 30, "BINAURAL", 0),
                    arrayOf("Flow State / Active Focus", 14.0, 14.0, 200.0, 25, "ISOCHRONIC", 0)
                )

                for (preset in defaults) {
                    db.execSQL(insertSql, preset)
                }
                db.setTransactionSuccessful()
                Log.i("AppDatabase", "Successfully seeded default meditation presets into SQLite table.")
            } catch (e: Exception) {
                Log.e("AppDatabase", "Failed to seed default presets during onCreate", e)
            } finally {
                db.endTransaction()
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Step 1: Create new table without DEFAULT clauses to match PresetEntity Room hash exactly
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `presets_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `startBeatHz` REAL NOT NULL,
                        `targetBeatHz` REAL NOT NULL,
                        `carrierHz` REAL NOT NULL,
                        `durationMinutes` INTEGER NOT NULL,
                        `soundMode` TEXT NOT NULL,
                        `enablePinkNoise` INTEGER NOT NULL
                    )
                """.trimIndent())

                // Step 2: Migrate data and transform legacy boolean columns to soundMode
                db.execSQL("""
                    INSERT INTO `presets_new` (`id`, `title`, `startBeatHz`, `targetBeatHz`, `carrierHz`, `durationMinutes`, `soundMode`, `enablePinkNoise`)
                    SELECT `id`, `title`, `startBeatHz`, `targetBeatHz`, `carrierHz`, `durationMinutes`,
                        CASE 
                            WHEN `isIsochronic` = 1 THEN 'ISOCHRONIC'
                            WHEN `enablePinkNoise` = 1 THEN 'HEMI_SYNC'
                            ELSE 'BINAURAL'
                        END AS `soundMode`,
                        `enablePinkNoise`
                    FROM `presets`
                """.trimIndent())

                // Step 3: Drop deprecated legacy table
                db.execSQL("DROP TABLE `presets`")

                // Step 4: Rename reconstructed table to active entity name
                db.execSQL("ALTER TABLE `presets_new` RENAME TO `presets`")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "black_cloud_binaural_db"
                )
                .addMigrations(MIGRATION_1_2)
                .addCallback(DatabaseCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

---

## `app/src/androidTest/java/com/blackcloudgroup/binaural/AppDatabaseMigrationTest.kt`
*(Reviewed — structurally sound, not yet confirmed passing on a real device. Run this first after setup.)*
```kotlin
package com.blackcloudgroup.binaural

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blackcloudgroup.binaural.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val testDbName = "migration-test-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_preservesDataAndTransformsSoundMode() {
        // 1. Create Version 1 database from schemas/1.json and seed fixture rows
        helper.createDatabase(testDbName, 1).apply {
            execSQL("""
                INSERT INTO presets (id, title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, isIsochronic, enablePinkNoise)
                VALUES (1, 'Legacy Isochronic Preset', 10.0, 10.0, 200.0, 15, 1, 0)
            """.trimIndent())
            execSQL("""
                INSERT INTO presets (id, title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, isIsochronic, enablePinkNoise)
                VALUES (2, 'Legacy Hemi-Sync Preset', 6.0, 6.0, 136.1, 20, 0, 1)
            """.trimIndent())
            execSQL("""
                INSERT INTO presets (id, title, startBeatHz, targetBeatHz, carrierHz, durationMinutes, isIsochronic, enablePinkNoise)
                VALUES (3, 'Legacy Plain Binaural Preset', 4.0, 4.0, 210.0, 30, 0, 0)
            """.trimIndent())
            close()
        }

        // 2. Run MIGRATION_1_2 and validate schema automatically against schemas/2.json
        val db = helper.runMigrationsAndValidate(testDbName, 2, true, AppDatabase.MIGRATION_1_2)

        // 3. Verify data integrity and column transformations
        val cursor = db.query("SELECT id, title, soundMode, enablePinkNoise FROM presets ORDER BY id ASC")
        assertTrue("Cursor should return rows", cursor.moveToFirst())

        // Row 1: isIsochronic = 1 -> ISOCHRONIC
        assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals("Legacy Isochronic Preset", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("ISOCHRONIC", cursor.getString(cursor.getColumnIndexOrThrow("soundMode")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("enablePinkNoise")))

        // Row 2: isIsochronic = 0, enablePinkNoise = 1 -> HEMI_SYNC
        assertTrue(cursor.moveToNext())
        assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals("Legacy Hemi-Sync Preset", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("HEMI_SYNC", cursor.getString(cursor.getColumnIndexOrThrow("soundMode")))
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("enablePinkNoise")))

        // Row 3: isIsochronic = 0, enablePinkNoise = 0 -> BINAURAL
        assertTrue(cursor.moveToNext())
        assertEquals(3L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals("Legacy Plain Binaural Preset", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("BINAURAL", cursor.getString(cursor.getColumnIndexOrThrow("soundMode")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("enablePinkNoise")))

        cursor.close()
        db.close()
    }
}
```

**Note on `schemas/1.json`:** this test's `helper.createDatabase(testDbName, 1)` call needs a v1 schema fixture present in `app/schemas/com.blackcloudgroup.binaural.data.AppDatabase/1.json`. Since this is a fresh project starting straight at version 2, that file won't exist yet. To generate it: temporarily set `PresetEntity` back to the original shape (`isIsochronic: Boolean` instead of `soundMode: String`) and `@Database(version = 1, ...)`, build once, copy the resulting `1.json` into the schemas folder, then revert both files to the version-2 state shown above.

---

## `app/src/main/java/com/blackcloudgroup/binaural/ui/PresetDialog.kt`
*(Phase 2 final — 3-way `FilterChip` selector, constructs `PresetEntity` with `soundMode`.)*
```kotlin
package com.blackcloudgroup.binaural.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blackcloudgroup.binaural.SoundMode
import com.blackcloudgroup.binaural.data.PresetEntity

@Composable
fun PresetDialog(
    onDismiss: () -> Unit,
    onSave: (PresetEntity) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var carrier by remember { mutableFloatStateOf(200f) }
    var startBeat by remember { mutableFloatStateOf(10f) }
    var targetBeat by remember { mutableFloatStateOf(2f) }
    var duration by remember { mutableIntStateOf(20) }
    var selectedMode by remember { mutableStateOf(SoundMode.HEMI_SYNC) }
    var enablePinkNoise by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Custom Preset") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Preset Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Sound Mode", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FilterChip(
                        selected = selectedMode == SoundMode.HEMI_SYNC,
                        onClick = { selectedMode = SoundMode.HEMI_SYNC },
                        label = { Text("Hemi-Sync") }
                    )
                    FilterChip(
                        selected = selectedMode == SoundMode.BINAURAL,
                        onClick = { selectedMode = SoundMode.BINAURAL },
                        label = { Text("Binaural") }
                    )
                    FilterChip(
                        selected = selectedMode == SoundMode.ISOCHRONIC,
                        onClick = { selectedMode = SoundMode.ISOCHRONIC },
                        label = { Text("Isochronic") }
                    )
                }

                Text("Carrier Frequency: ${carrier.toInt()} Hz")
                Slider(
                    value = carrier,
                    onValueChange = { carrier = it },
                    valueRange = 100f..500f
                )

                Text("Start Beat: ${String.format("%.1f", startBeat)} Hz")
                Slider(
                    value = startBeat,
                    onValueChange = { startBeat = it },
                    valueRange = 0.5f..40f
                )

                Text("Target Beat (Ramp End): ${String.format("%.1f", targetBeat)} Hz")
                Slider(
                    value = targetBeat,
                    onValueChange = { targetBeat = it },
                    valueRange = 0.5f..40f
                )

                Text("Duration: $duration Minutes")
                Slider(
                    value = duration.toFloat(),
                    onValueChange = { duration = it.toInt() },
                    valueRange = 1f..60f
                )

                if (selectedMode == SoundMode.HEMI_SYNC) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pink Noise Masking")
                        Switch(checked = enablePinkNoise, onCheckedChange = { enablePinkNoise = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank(),
                onClick = {
                    val newPreset = PresetEntity(
                        title = title.trim(),
                        startBeatHz = startBeat.toDouble(),
                        targetBeatHz = targetBeat.toDouble(),
                        carrierHz = carrier.toDouble(),
                        durationMinutes = duration,
                        soundMode = selectedMode.name,
                        enablePinkNoise = enablePinkNoise
                    )
                    onSave(newPreset)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

---

## `app/src/main/java/com/blackcloudgroup/binaural/MainActivity.kt`
*(Phase 1 + Phase 2 final — `mutableStateOf` service binding, `SoundMode.valueOf(preset.soundMode)` with safe fallback.)*
```kotlin
package com.blackcloudgroup.binaural

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.blackcloudgroup.binaural.data.AppDatabase
import com.blackcloudgroup.binaural.data.PresetEntity
import com.blackcloudgroup.binaural.ui.LissajousVisualizer
import com.blackcloudgroup.binaural.ui.PhoticEntrainmentCanvas
import com.blackcloudgroup.binaural.ui.PresetDialog
import com.blackcloudgroup.binaural.util.WavExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private var audioService by mutableStateOf<BinauralAudioService?>(null)
    private var isBound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as BinauralAudioService.LocalBinder
            audioService = binder.getService()
            isBound = true
            Log.i("MainActivity", "BinauralAudioService connected and bound successfully.")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            audioService = null
            isBound = false
            Log.w("MainActivity", "BinauralAudioService disconnected unexpectedly.")
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, BinauralAudioService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            audioService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getDatabase(this)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppContent(
                        audioService = audioService,
                        database = database
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(
    audioService: BinauralAudioService?,
    database: AppDatabase
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val presets by database.presetDao().getAllPresets().collectAsState(initial = emptyList())

    var isPlaying by remember { mutableStateOf(false) }
    var carrier by remember { mutableFloatStateOf(200f) }
    var beat by remember { mutableFloatStateOf(6f) }
    var soundMode by remember { mutableStateOf(SoundMode.HEMI_SYNC) }
    var enablePhotic by remember { mutableStateOf(false) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<PresetEntity?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        PhoticEntrainmentCanvas(beatFreqHz = beat.toDouble(), isEnabled = enablePhotic && isPlaying)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Black Cloud Binaural",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LissajousVisualizer(
                carrierHz = carrier.toDouble(),
                beatHz = beat.toDouble(),
                isPlaying = isPlaying
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Sound Mode Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FilterChip(
                    selected = soundMode == SoundMode.HEMI_SYNC,
                    onClick = {
                        soundMode = SoundMode.HEMI_SYNC
                        audioService?.soundMode = SoundMode.HEMI_SYNC
                    },
                    label = { Text("Hemi-Sync") }
                )
                FilterChip(
                    selected = soundMode == SoundMode.BINAURAL,
                    onClick = {
                        soundMode = SoundMode.BINAURAL
                        audioService?.soundMode = SoundMode.BINAURAL
                    },
                    label = { Text("Binaural") }
                )
                FilterChip(
                    selected = soundMode == SoundMode.ISOCHRONIC,
                    onClick = {
                        soundMode = SoundMode.ISOCHRONIC
                        audioService?.soundMode = SoundMode.ISOCHRONIC
                    },
                    label = { Text("Isochronic") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Carrier Frequency: ${carrier.toInt()} Hz")
            Slider(
                value = carrier,
                onValueChange = {
                    carrier = it
                    audioService?.carrierFreq = it.toDouble()
                },
                valueRange = 100f..500f
            )

            Text("Binaural Beat: ${String.format("%.1f", beat)} Hz")
            Slider(
                value = beat,
                onValueChange = {
                    beat = it
                    audioService?.beatFreq = it.toDouble()
                },
                valueRange = 0.5f..40f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Photic Light Flashing")
                Switch(checked = enablePhotic, onCheckedChange = { enablePhotic = it })
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = audioService != null,
                    onClick = {
                        val service = audioService
                        if (service == null) {
                            Log.w("MainActivity", "Playback toggle failed: Audio service is unbound.")
                            Toast.makeText(context, "Audio service initializing...", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (isPlaying) {
                            service.stopAudio()
                            isPlaying = false
                        } else {
                            val intent = Intent(context, BinauralAudioService::class.java)
                            context.startForegroundService(intent)
                            service.carrierFreq = carrier.toDouble()
                            service.beatFreq = beat.toDouble()
                            service.soundMode = soundMode
                            service.startAudio()
                            isPlaying = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (audioService == null) "Connecting..." 
                        else if (isPlaying) "Stop Session" 
                        else "Start Session"
                    )
                }

                OutlinedButton(onClick = { showPresetDialog = true }) {
                    Text("Save Preset")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Saved Presets",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(presets) { preset ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            carrier = preset.carrierHz.toFloat()
                            beat = preset.startBeatHz.toFloat()
                            soundMode = try {
                                SoundMode.valueOf(preset.soundMode)
                            } catch (e: IllegalArgumentException) {
                                SoundMode.BINAURAL
                            }

                            audioService?.let { service ->
                                service.carrierFreq = preset.carrierHz
                                service.beatFreq = preset.startBeatHz
                                service.soundMode = soundMode
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(preset.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${preset.soundMode} | ${preset.carrierHz}Hz Base | ${preset.startBeatHz}Hz Beat | ${preset.durationMinutes}m",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            try {
                                                val outFile = File(context.cacheDir, "${preset.title.replace(" ", "_")}.wav")
                                                WavExporter.exportToWav(context, preset, outFile)
                                                launch(Dispatchers.Main) {
                                                    try {
                                                        val uri = FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.fileprovider",
                                                            outFile
                                                        )
                                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "audio/wav"
                                                            putExtra(Intent.EXTRA_STREAM, uri)
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        context.startActivity(Intent.createChooser(shareIntent, "Share WAV export"))
                                                    } catch (e: Exception) {
                                                        Log.e("MainActivity", "Failed to share exported WAV", e)
                                                        Toast.makeText(context, "Exported to ${outFile.name}, but sharing failed", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e("MainActivity", "Failed to export WAV file", e)
                                                launch(Dispatchers.Main) {
                                                    Toast.makeText(context, "Export failed: ${e.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text("WAV")
                                }

                                IconButton(onClick = { presetToDelete = preset }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Preset",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showPresetDialog) {
            PresetDialog(
                onDismiss = { showPresetDialog = false },
                onSave = { newPreset ->
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            database.presetDao().insertPreset(newPreset)
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Failed to insert custom preset", e)
                        }
                    }
                    showPresetDialog = false
                }
            )
        }

        presetToDelete?.let { preset ->
            AlertDialog(
                onDismissRequest = { presetToDelete = null },
                title = { Text("Delete Preset") },
                text = { Text("Are you sure you want to delete \"${preset.title}\"? This action cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                try {
                                    database.presetDao().deletePreset(preset)
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to delete preset from database", e)
                                }
                            }
                            presetToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { presetToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
```

---

## `app/src/main/java/com/blackcloudgroup/binaural/BinauralAudioService.kt`
**⚠️ UNCHANGED FROM ORIGINAL — Phase 3 not yet applied.** Still carries: unclamped sample values in the Hemi-Sync synthesis loop (potential distortion/clipping), a stop()/write() race condition that can crash on rapid stop, and silent no-op on audio-focus denial. Also still defines `enum class SoundMode { BINAURAL, ISOCHRONIC, HEMI_SYNC }`, referenced by `MainActivity.kt` and `PresetDialog.kt` above.
```kotlin
package com.blackcloudgroup.binaural

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread
import kotlin.math.sin

enum class SoundMode { BINAURAL, ISOCHRONIC, HEMI_SYNC }

class BinauralAudioService : Service(), AudioManager.OnAudioFocusChangeListener {

    private val binder = LocalBinder()

    @Volatile
    private var isPlaying = false

    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private lateinit var mediaSession: MediaSessionCompat
    private var wakeLock: PowerManager.WakeLock? = null

    var carrierFreq = 200.0
    var beatFreq = 6.0
    var soundMode = SoundMode.HEMI_SYNC

    private var isDucked = false

    var targetVolume = 0.2f
        set(value) {
            field = value
            masterVolume = if (isDucked) value * 0.5f else value
        }

    var masterVolume = 0.2f
        private set

    inner class LocalBinder : Binder() {
        fun getService(): BinauralAudioService = this@BinauralAudioService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        mediaSession = MediaSessionCompat(this, "BinauralAudioService").apply {
            isActive = true
        }

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "BlackCloudBinaural::AudioSynthesisWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(120 * 60 * 1000L) // 2-hour safety timeout limit
        } catch (e: SecurityException) {
            Log.e("BinauralAudioService", "Failed to acquire PARTIAL_WAKE_LOCK: Permission denied", e)
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Unexpected error acquiring WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Error releasing WakeLock", e)
        }
    }

    private fun requestAudioFocus(): Boolean {
        return try {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()

            val res = audioManager.requestAudioFocus(focusRequest!!)
            res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Audio focus request failed explicitly", e)
            false
        }
    }

    private fun abandonAudioFocus() {
        try {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Error abandoning audio focus", e)
        } finally {
            focusRequest = null
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stopAudio()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                isDucked = true
                masterVolume = targetVolume * 0.5f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                isDucked = false
                masterVolume = targetVolume
            }
        }
    }

    fun startAudio() {
        if (isPlaying || !requestAudioFocus()) return
        acquireWakeLock()
        isPlaying = true

        val sampleRate = 44100
        val bufferSizeBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSizeBytes <= 0) {
            Log.e("BinauralAudioService", "Invalid AudioTrack min buffer size: $bufferSizeBytes")
            abandonAudioFocus()
            releaseWakeLock()
            isPlaying = false
            return
        }

        val bufferSizeShorts = bufferSizeBytes / 2

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()

            audioTrack?.play()
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Failed to initialize AudioTrack engine", e)
            abandonAudioFocus()
            releaseWakeLock()
            isPlaying = false
            return
        }

        audioThread = thread {
            var sampleIdx = 0L
            val buffer = ShortArray(bufferSizeShorts)
            val framesPerBuffer = bufferSizeShorts / 2

            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
            var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0

            while (isPlaying) {
                val leftFreq1 = carrierFreq - (beatFreq / 2.0)
                val rightFreq1 = carrierFreq + (beatFreq / 2.0)
                val harmonicCarrier = carrierFreq * 1.5
                val leftFreq2 = harmonicCarrier - (beatFreq / 2.0)
                val rightFreq2 = harmonicCarrier + (beatFreq / 2.0)

                val isIsochronic = soundMode == SoundMode.ISOCHRONIC
                val isHemiSync = soundMode == SoundMode.HEMI_SYNC
                val isochronicPeriod = sampleRate / beatFreq

                for (i in 0 until framesPerBuffer) {
                    var sampleL = sin(2.0 * Math.PI * sampleIdx * leftFreq1 / sampleRate)
                    var sampleR = sin(2.0 * Math.PI * sampleIdx * rightFreq1 / sampleRate)

                    if (isHemiSync) {
                        val sampleL2 = sin(2.0 * Math.PI * sampleIdx * leftFreq2 / sampleRate) * 0.5
                        val sampleR2 = sin(2.0 * Math.PI * sampleIdx * rightFreq2 / sampleRate) * 0.5

                        sampleL = (sampleL + sampleL2) / 1.5
                        sampleR = (sampleR + sampleR2) / 1.5

                        val white = Math.random() * 2.0 - 1.0
                        b0 = 0.99886 * b0 + white * 0.0555179
                        b1 = 0.99332 * b1 + white * 0.0750759
                        b2 = 0.96900 * b2 + white * 0.1538520
                        b3 = 0.86650 * b3 + white * 0.3104856
                        b4 = 0.55000 * b4 + white * 0.5329522
                        b5 = -0.7616 * b5 - white * 0.0168980
                        val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.02
                        b6 = white * 0.115926

                        sampleL += pink
                        sampleR += pink
                    } else if (isIsochronic) {
                        val phase = (sampleIdx % isochronicPeriod) / isochronicPeriod
                        val gain = if (phase < 0.5) 1.0 else 0.0
                        sampleL *= gain
                        sampleR *= gain
                    }

                    buffer[i * 2] = (sampleL * Short.MAX_VALUE * masterVolume).toInt().toShort()
                    buffer[i * 2 + 1] = (sampleR * Short.MAX_VALUE * masterVolume).toInt().toShort()
                    sampleIdx++
                }

                val track = audioTrack ?: break
                track.write(buffer, 0, buffer.size)
            }
        }
    }

    fun stopAudio() {
        isPlaying = false

        try {
            audioThread?.join(500)
        } catch (e: InterruptedException) {
            Log.e("BinauralAudioService", "Interrupted while joining audio thread", e)
            Thread.currentThread().interrupt()
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e("BinauralAudioService", "Error shutting down AudioTrack", e)
        } finally {
            audioTrack = null
            audioThread = null
            releaseWakeLock()
            abandonAudioFocus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopAudio()
        mediaSession.release()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "binaural_channel",
            "Binaural Beats",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "binaural_channel")
            .setContentTitle("Black Cloud Group | Binaural Session")
            .setContentText("Hemi-Sync & brainwave entrainment engine active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
```

---

## `app/src/main/java/com/blackcloudgroup/binaural/util/WavExporter.kt`
**⚠️ UNCHANGED FROM ORIGINAL — Phase 4 not yet applied.** Does not implement Hemi-Sync (harmonic layer + pink noise) — exported WAVs for Hemi-Sync presets will sound like plain binaural until Phase 4 runs. Also carries the same unclamped-sample pattern flagged for Phase 3.
```kotlin
package com.blackcloudgroup.binaural.util

import android.content.Context
import com.blackcloudgroup.binaural.data.PresetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object WavExporter {

    suspend fun exportToWav(
        context: Context,
        preset: PresetEntity,
        outputFile: File
    ) = withContext(Dispatchers.IO) {
        val sampleRate = 44100
        val totalSeconds = preset.durationMinutes * 60
        val numSamples = sampleRate * totalSeconds
        val numChannels = 2
        val bitsPerSample = 16
        val dataSize = numSamples * numChannels * (bitsPerSample / 8)

        FileOutputStream(outputFile).use { fos ->
            fos.write(createWavHeader(dataSize, sampleRate, numChannels, bitsPerSample))

            val bufferSize = 4096
            val buffer = ShortArray(bufferSize * numChannels)
            var sampleIdx = 0L

            while (sampleIdx < numSamples) {
                val progress = sampleIdx.toDouble() / numSamples
                val currentBeat = preset.startBeatHz + (preset.targetBeatHz - preset.startBeatHz) * progress
                val leftFreq = preset.carrierHz - (currentBeat / 2.0)
                val rightFreq = preset.carrierHz + (currentBeat / 2.0)

                val isochronicPeriod = sampleRate / currentBeat

                var i = 0
                while (i < bufferSize && sampleIdx < numSamples) {
                    var sampleL = sin(2.0 * Math.PI * sampleIdx * leftFreq / sampleRate)
                    var sampleR = sin(2.0 * Math.PI * sampleIdx * rightFreq / sampleRate)

                    if (preset.soundMode == "ISOCHRONIC") {
                        val phase = (sampleIdx % isochronicPeriod) / isochronicPeriod
                        val gain = if (phase < 0.5) 1.0 else 0.0
                        sampleL *= gain
                        sampleR *= gain
                    }

                    buffer[i * 2] = (sampleL * Short.MAX_VALUE * 0.2).toInt().toShort()
                    buffer[i * 2 + 1] = (sampleR * Short.MAX_VALUE * 0.2).toInt().toShort()
                    i++
                    sampleIdx++
                }

                val byteBuffer = ByteBuffer.allocate(i * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (j in 0 until i * 2) {
                    byteBuffer.putShort(buffer[j])
                }
                fos.write(byteBuffer.array())
            }
        }
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val totalSize = dataSize + 36
        val byteRate = sampleRate * channels * (bits / 8)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray())
        header.putInt(totalSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * (bits / 8)).toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray())
        header.putInt(dataSize)

        return header.array()
    }
}
```
**Note:** this version has already been hand-patched from the original to reference `preset.soundMode == "ISOCHRONIC"` instead of the now-deleted `preset.isIsochronic` field — otherwise this file wouldn't compile against the current `PresetEntity`. This single-line patch was necessary just to keep the bundle buildable; it was not part of a reviewed Gemini prompt, so treat it as unverified until Phase 4 properly reviews this file.

---

## `app/src/main/java/com/blackcloudgroup/binaural/health/HealthConnectManager.kt`
*(Unchanged — dead code, not yet wired up. Phase 5 territory.)*
```kotlin
package com.blackcloudgroup.binaural.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.MindfulnessSessionRecord
import java.time.Instant

class HealthConnectManager(private val context: Context) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun writeMindfulnessSession(startTime: Instant, endTime: Instant, title: String) {
        try {
            val record = MindfulnessSessionRecord(
                startTime = startTime,
                startZoneOffset = null,
                endTime = endTime,
                endZoneOffset = null,
                mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION,
                notes = title
            )
            healthConnectClient.insertRecords(listOf(record))
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Failed to write mindfulness session to Health Connect", e)
        }
    }
}
```

---

## `app/src/main/java/com/blackcloudgroup/binaural/ui/PhoticEntrainmentCanvas.kt`
*(Unchanged from original.)*
```kotlin
package com.blackcloudgroup.binaural.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun PhoticEntrainmentCanvas(beatFreqHz: Double, isEnabled: Boolean) {
    if (!isEnabled) return

    val infiniteTransition = rememberInfiniteTransition(label = "photic")
    val durationMillis = (1000.0 / beatFreqHz).toInt().coerceAtLeast(16)

    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Cyan.copy(alpha = alpha))
    )
}
```

---

## `app/src/main/java/com/blackcloudgroup/binaural/ui/LissajousVisualizer.kt`
*(Unchanged from original.)*
```kotlin
package com.blackcloudgroup.binaural.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun LissajousVisualizer(
    carrierHz: Double,
    beatHz: Double,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(220.dp)
) {
    var timePhase by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(isPlaying, carrierHz, beatHz) {
        if (isPlaying) {
            while (true) {
                timePhase += 0.03
                withFrameNanos { }
            }
        }
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) * 0.8f

        if (!isPlaying) {
            drawCircle(
                color = Color.DarkGray,
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2f)
            )
            return@Canvas
        }

        val leftFreq = carrierHz - (beatHz / 2.0)
        val rightFreq = carrierHz + (beatHz / 2.0)
        val freqRatio = rightFreq / leftFreq

        val path = Path()
        val pointCount = 300

        for (i in 0..pointCount) {
            val t = (i.toDouble() / pointCount) * (2.0 * Math.PI)
            val x = centerX + radius * sin(t * freqRatio + timePhase).toFloat()
            val y = centerY + radius * sin(t).toFloat()

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = Color(0xFF3B82F6),
            style = Stroke(width = 3f)
        )
    }
}
```

---

## Setup checklist, in order

1. Create the Android Studio project shell (Empty Activity, Compose, package `com.blackcloudgroup.binaural`, min SDK 26).
2. Drop in every file above at its listed path.
3. Build once (`gradlew.bat assembleDebug`) — confirms compile and generates `schemas/2.json`.
4. Backfill `schemas/1.json` per the note under the migration test section above.
5. Run `gradlew.bat connectedAndroidTest` on a device/emulator — this is what finally closes Phase 2.
6. Once green, move to Phase 3 (`BinauralAudioService.kt` correctness fixes) using the prompt already in the MVP plan doc.
