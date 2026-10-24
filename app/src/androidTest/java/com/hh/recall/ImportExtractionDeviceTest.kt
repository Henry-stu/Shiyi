package com.hh.recall

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hh.recall.importing.OcrImporter
import com.hh.recall.importing.TextImporter
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ImportExtractionDeviceTest {
    @Test fun integratedOcrMatchesTenPreviouslyVerifiedLabOutputs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val cases = JSONArray(assets.open("import/gate.json").bufferedReader().use { it.readText() })
        OcrImporter(instrumentation.targetContext).use { engine ->
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                try {
                    assets.open(case.getString("asset")).use { input ->
                        engine.read(input, case.getString("family"), AtomicBoolean()) { _, _, _ -> }.use { result ->
                            assertTrue(case.getString("name"), case.isNull("error"))
                            val hash = MessageDigest.getInstance("SHA-256").digest(result.body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
                            assertEquals(case.getString("name"), case.getString("sha256"), hash)
                        }
                    }
                } catch (failure: TextImporter.Failure) {
                    assertEquals(case.getString("name"), case.getString("error"), failure.code)
                }
            }
        }
        assertEquals(10, cases.length())
    }
}
