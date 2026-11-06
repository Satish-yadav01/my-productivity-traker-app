package com.myproductivity.tracker

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object SpreadsheetSetup {
    private val spreadsheetUrlPattern =
        Regex("""^https?://docs\.google\.com/spreadsheets/d/([A-Za-z0-9_-]+)(?:/|$)""")

    val activityHeaders = listOf(
        "Activity_ID", "Date", "Activity", "Category", "Priority",
        "Planned_Start", "Planned_End", "Planned_Duration_Min",
        "Status", "Actual_Duration_Min", "Notes", "Created_At", "Updated_At"
    )
    val timeLogHeaders = listOf(
        "Log_ID", "Activity_ID", "Date", "Activity",
        "Start_Time", "End_Time", "Duration_Min", "Notes"
    )

    fun extractSpreadsheetId(input: String): String? =
        spreadsheetUrlPattern.find(input.trim())?.groupValues?.getOrNull(1)

    fun buildConfiguredCode(template: String, spreadsheetId: String, secret: String): String =
        template
            .replace("PASTE_SPREADSHEET_ID_HERE", spreadsheetId)
            .replace(
                "REPLACE_WITH_GENERATED_SECRET",
                secret.replace("\\", "\\\\").replace("\"", "\\\"")
            )
}

internal object TemplateWorkbook {
    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""
    private const val PACKAGE_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
    private const val WORKBOOK =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Activities" sheetId="1" r:id="rId1"/><sheet name="Time_Log" sheetId="2" r:id="rId2"/></sheets>
</workbook>"""
    private const val WORKBOOK_RELS =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    private const val STYLES =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="2"><font><sz val="11"/><name val="Arial"/></font><font><b/><color rgb="FF000000"/><sz val="11"/><name val="Arial"/></font></fonts>
<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFF9900"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FF7F6000"/></left><right style="thin"><color rgb="FF7F6000"/></right><top style="thin"><color rgb="FF7F6000"/></top><bottom style="thin"><color rgb="FF7F6000"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="1" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
<dxfs count="12">
<dxf><font><color rgb="FF0066CC"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFB7DDF2"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FF00843D"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFC6EFD6"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FF7F6000"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFFFE699"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FFCC0000"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFF4B6B6"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FF7030A0"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFE4D7F5"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FFCC6600"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFFFD7B5"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FFCC6600"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFFFD7B5"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FF54717A"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFCBDDE1"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FF0066CC"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFB7DDF2"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FFCC0000"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFF4B6B6"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FF0066CC"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFB7DDF2"/><bgColor indexed="64"/></patternFill></fill></dxf>
<dxf><font><color rgb="FF00843D"/></font><fill><patternFill patternType="solid"><fgColor rgb="FFC6EFD6"/><bgColor indexed="64"/></patternFill></fill></dxf>
</dxfs>
</styleSheet>"""

    fun write(output: OutputStream) {
        val zip = ZipOutputStream(output)
        zip.putEntry("[Content_Types].xml", CONTENT_TYPES)
        zip.putEntry("_rels/.rels", PACKAGE_RELS)
        zip.putEntry("xl/workbook.xml", WORKBOOK)
        zip.putEntry("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
        zip.putEntry("xl/styles.xml", STYLES)
        zip.putEntry(
            "xl/worksheets/sheet1.xml",
            sheetXml(SpreadsheetSetup.activityHeaders, activityValidations(), activityColors())
        )
        zip.putEntry("xl/worksheets/sheet2.xml", sheetXml(SpreadsheetSetup.timeLogHeaders))
        zip.finish()
        zip.flush()
    }

    private fun sheetXml(
        headers: List<String>,
        validations: List<Validation> = emptyList(),
        conditionalFormatting: List<ColorRule> = emptyList()
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("""<sheetFormatPr defaultRowHeight="20"/><cols>""")
        headers.forEachIndexed { index, _ ->
            val width = when (index) {
                0 -> 22.0
                1 -> 14.0
                2 -> 40.0
                3 -> 24.0
                4 -> 14.0
                else -> 20.0
            }
            append("""<col min="${index + 1}" max="${index + 1}" width="$width" customWidth="1"/>""")
        }
        append("</cols><sheetData><row r=\"1\" ht=\"32\" customHeight=\"1\">")
        headers.forEachIndexed { index, header ->
            append("""<c r="${columnName(index + 1)}1" s="1" t="inlineStr"><is><t>${xmlEscape(header)}</t></is></c>""")
        }
        append("</row></sheetData>")
        append("""<autoFilter ref="A1:${columnName(headers.size)}1000"/>""")
        if (conditionalFormatting.isNotEmpty()) {
            conditionalFormatting.forEach { rule ->
                append("""<conditionalFormatting sqref="${rule.range}"><cfRule type="cellIs" dxfId="${rule.styleId}" priority="${rule.priority}"><formula>""")
                append(xmlEscape("\"${rule.value}\""))
                append("</formula></cfRule></conditionalFormatting>")
            }
        }
        if (validations.isNotEmpty()) {
            append("""<dataValidations count="${validations.size}">""")
            validations.forEach { validation ->
                append("""<dataValidation type="list" allowBlank="1" showErrorMessage="1" errorTitle="Choose from the list" error="Select one of the available options." sqref="${validation.range}"><formula1>""")
                append(xmlEscape("\"${validation.options.joinToString(",")}\""))
                append("</formula1></dataValidation>")
            }
            append("</dataValidations>")
        }
        append("</worksheet>")
    }

    private data class Validation(val range: String, val options: List<String>)
    private data class ColorRule(val range: String, val value: String, val styleId: Int, val priority: Int)

    private fun activityValidations() = listOf(
        Validation("D2:D1000", Config.CATEGORIES),
        Validation("E2:E1000", Config.PRIORITIES),
        Validation("I2:I1000", Config.STATUSES)
    )

    private fun activityColors() = listOf(
        ColorRule("D2:D1000", Config.CATEGORIES[0], 0, 1),
        ColorRule("D2:D1000", Config.CATEGORIES[1], 1, 2),
        ColorRule("D2:D1000", Config.CATEGORIES[2], 4, 3),
        ColorRule("D2:D1000", Config.CATEGORIES[3], 3, 4),
        ColorRule("D2:D1000", Config.CATEGORIES[4], 2, 5),
        ColorRule("E2:E1000", Config.PRIORITIES[0], 6, 6),
        ColorRule("E2:E1000", Config.PRIORITIES[1], 7, 7),
        ColorRule("E2:E1000", Config.PRIORITIES[2], 2, 8),
        ColorRule("I2:I1000", Config.STATUSES[0], 10, 9),
        ColorRule("I2:I1000", Config.STATUSES[1], 8, 10),
        ColorRule("I2:I1000", Config.STATUSES[2], 11, 11)
    )

    private fun columnName(number: Int): String {
        var value = number
        val result = StringBuilder()
        while (value > 0) {
            val remainder = (value - 1) % 26
            result.insert(0, ('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return result.toString()
    }

    private fun xmlEscape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun ZipOutputStream.putEntry(path: String, contents: String) {
        putNextEntry(ZipEntry(path))
        write(contents.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
