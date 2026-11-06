package com.myproductivity.tracker

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadsheetSetupTest {
    @Test
    fun extractsIdFromSpreadsheetUrl() {
        assertEquals(
            "abc-123_X",
            SpreadsheetSetup.extractSpreadsheetId(
                "https://docs.google.com/spreadsheets/d/abc-123_X/edit?usp=sharing"
            )
        )
    }

    @Test
    fun rejectsNonSpreadsheetUrls() {
        assertNull(SpreadsheetSetup.extractSpreadsheetId("https://example.com/spreadsheets/d/abc/edit"))
        assertNull(SpreadsheetSetup.extractSpreadsheetId("https://docs.google.com/spreadsheets/abc/edit"))
        assertNull(SpreadsheetSetup.extractSpreadsheetId("https://docs.google.com.evil.example/spreadsheets/d/abc/edit"))
    }

    @Test
    fun buildsScriptWithSpreadsheetIdAndSecret() {
        val configured = SpreadsheetSetup.buildConfiguredCode(
            """SPREADSHEET_ID: "PASTE_SPREADSHEET_ID_HERE", API_SECRET: "REPLACE_WITH_GENERATED_SECRET"""",
            "sheet-id_123",
            "stored-secret"
        )
        assertEquals(
            """SPREADSHEET_ID: "sheet-id_123", API_SECRET: "stored-secret"""",
            configured
        )
    }

    @Test
    fun writesValidWorkbookPartsAndMatchingHeaders() {
        val output = ByteArrayOutputStream()
        TemplateWorkbook.write(output)

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = zip.readBytes().toString(Charsets.UTF_8)
                if (entry.name.endsWith(".xml") || entry.name.endsWith(".rels")) {
                    DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(InputSource(StringReader(content)))
                }
                entries[entry.name] = content
            }
        }

        assertNotNull(entries["[Content_Types].xml"])
        assertTrue(entries["xl/workbook.xml"].orEmpty().contains("""name="Activities""""))
        assertTrue(entries["xl/workbook.xml"].orEmpty().contains("""name="Time_Log""""))
        assertTrue(entries["xl/styles.xml"].orEmpty().contains("FFFF9900"))
        val activities = entries["xl/worksheets/sheet1.xml"].orEmpty()
        val timeLog = entries["xl/worksheets/sheet2.xml"].orEmpty()
        SpreadsheetSetup.activityHeaders.forEach { assertTrue(activities.contains("<t>$it</t>")) }
        SpreadsheetSetup.timeLogHeaders.forEach { assertTrue(timeLog.contains("<t>$it</t>")) }
        assertTrue(activities.contains("""sqref="D2:D1000""""))
        assertTrue(activities.contains("""sqref="E2:E1000""""))
        assertTrue(activities.contains("""sqref="I2:I1000""""))
        assertTrue(activities.contains("<dataValidations count=\"3\">"))
        assertTrue(activities.contains("Career &amp; Learning"))
        assertTrue(activities.contains("conditionalFormatting"))
        assertTrue(activities.contains("""<autoFilter ref="A1:M1000"/>"""))
        assertTrue(activities.contains("""state="frozen""""))
        assertFalse(activities.contains("REPLACE_WITH"))
    }
}
