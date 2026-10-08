package com.foton.crawlermetraj

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PoleXlsxExporter {

    fun build(records: List<PoleRecord>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            add(zip, "[Content_Types].xml", contentTypes())
            add(zip, "_rels/.rels", rootRels())
            add(zip, "xl/workbook.xml", workbook())
            add(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            add(zip, "xl/styles.xml", styles())
            add(zip, "xl/worksheets/sheet1.xml", worksheet(records))
        }
        return output.toByteArray()
    }

    private fun add(zip: ZipOutputStream, path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbook() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets><sheet name="Pole Metraj" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="1"><numFmt numFmtId="164" formatCode="0.00"/></numFmts>
  <fonts count="3">
    <font><sz val="10"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><color rgb="FFFFFFFF"/><sz val="10"/><name val="Calibri"/><family val="2"/></font>
    <font><b/><color rgb="FF174A5B"/><sz val="10"/><name val="Calibri"/><family val="2"/></font>
  </fonts>
  <fills count="4">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF174A5B"/><bgColor indexed="64"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFEAF1F4"/><bgColor indexed="64"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border><left style="thin"><color rgb="FFD7DEE3"/></left><right style="thin"><color rgb="FFD7DEE3"/></right><top style="thin"><color rgb="FFD7DEE3"/></top><bottom style="thin"><color rgb="FFD7DEE3"/></bottom><diagonal/></border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="7">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" shrinkToFit="1"/></xf>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center" shrinkToFit="1"/></xf>
    <xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="164" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
  </cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    private fun worksheet(records: List<PoleRecord>): String {
        val headers = listOf(
            "Tarih", "Firma", "İş Adı", "Mahal", "Hat", "Boru Tipi", "Malzeme", "Çap", "Yön", "Metraj", "Durum",
            "Video", "Foto", "Boylam", "Enlem", "Video Saati", "Foto Saati", "Fark sn"
        )
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\" zoomScale=\"80\" zoomScaleNormal=\"80\" showGridLines=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"18\"/><cols>")
        val widths = listOf(11.0, 13.0, 17.0, 10.0, 14.0, 13.0, 11.0, 7.0, 8.0, 10.0, 15.0, 20.0, 20.0, 10.0, 10.0, 11.0, 11.0, 8.0)
        widths.forEachIndexed { i, w ->
            val hidden = if (i >= 11) " hidden=\"1\"" else ""
            sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"$hidden/>")
        }
        sb.append("</cols><sheetData>")

        var row = 1
        appendRow(sb, row++, headers.map { Cell.Text(it, STYLE_HEADER) }, 26.0)
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        records.forEach { r ->
            val v = r.video
            appendRow(
                sb, row++, listOf(
                    Cell.Text(v.tarih ?: "", STYLE_TEXT),
                    Cell.Text(v.isinSahibi ?: "", STYLE_TEXT),
                    Cell.Text(v.isAdi ?: "", STYLE_WRAP),
                    Cell.Text(v.mahal ?: "", STYLE_TEXT),
                    Cell.Text(v.hat ?: "", STYLE_TEXT),
                    Cell.Text(v.boruTipi ?: "", STYLE_TEXT),
                    Cell.Text(v.boruMalzemesi ?: "", STYLE_TEXT),
                    v.capMm?.let { Cell.Number(it.toDouble(), STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT),
                    Cell.Text(v.yon ?: "", STYLE_TEXT),
                    r.distanceM?.let { Cell.Number(it, STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT),
                    Cell.Text(r.status, STYLE_WRAP),
                    Cell.Text(v.fileName, STYLE_WRAP),
                    Cell.Text(r.photoFileName ?: "", STYLE_WRAP),
                    v.boylam?.let { Cell.Number(it, STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT),
                    v.enlem?.let { Cell.Number(it, STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT),
                    Cell.Text(v.timestampMs?.let { timeFmt.format(Date(it)) } ?: "", STYLE_TEXT),
                    Cell.Text(r.photoTimestampMs?.let { timeFmt.format(Date(it)) } ?: "", STYLE_TEXT),
                    r.matchDeltaSec?.let { Cell.Number(it.toDouble(), STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT)
                ), 20.0
            )
        }

        val total = records.mapNotNull { it.distanceM }.sum()
        appendRow(
            sb, row,
            listOf(
                Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT),
                Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT),
                Cell.Text("TOPLAM", STYLE_TOTAL_LABEL), Cell.Number(total, STYLE_TOTAL_NUMBER), Cell.Text("", STYLE_TEXT),
                Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT),
                Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT)
            ), 22.0
        )

        val dataLast = maxOf(1, row - 1)
        sb.append("</sheetData><autoFilter ref=\"A1:K$dataLast\"/>")
        sb.append("<pageMargins left=\"0.25\" right=\"0.25\" top=\"0.4\" bottom=\"0.4\" header=\"0.2\" footer=\"0.2\"/>")
        sb.append("<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/></worksheet>")
        return sb.toString()
    }

    private const val STYLE_HEADER = 1
    private const val STYLE_TEXT = 2
    private const val STYLE_NUMBER = 3
    private const val STYLE_WRAP = 4
    private const val STYLE_TOTAL_LABEL = 5
    private const val STYLE_TOTAL_NUMBER = 6

    private sealed class Cell {
        data class Text(val value: String, val style: Int) : Cell()
        data class Number(val value: Double, val style: Int) : Cell()
    }

    private fun appendRow(sb: StringBuilder, row: Int, cells: List<Cell>, height: Double?) {
        if (height != null) sb.append("<row r=\"$row\" ht=\"$height\" customHeight=\"1\">") else sb.append("<row r=\"$row\">")
        cells.forEachIndexed { i, cell ->
            val ref = "${columnName(i + 1)}$row"
            when (cell) {
                is Cell.Text -> sb.append("<c r=\"$ref\" s=\"${cell.style}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(cell.value)}</t></is></c>")
                is Cell.Number -> sb.append("<c r=\"$ref\" s=\"${cell.style}\"><v>${cell.value}</v></c>")
            }
        }
        sb.append("</row>")
    }

    private fun columnName(number: Int): String {
        var n = number
        val out = StringBuilder()
        while (n > 0) {
            n--
            out.append(('A'.code + (n % 26)).toChar())
            n /= 26
        }
        return out.reverse().toString()
    }

    private fun xmlEscape(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
