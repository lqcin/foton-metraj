package com.foton.crawlermetraj

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tablet odaklı kompakt XLSX çıktısı.
 * - Sabit ve dar kolon genişlikleri
 * - Uzun video/durum metinlerinde satır kaydırma
 * - Başlık stili, ince kenarlıklar, iki ondalık sayı biçimi
 * - İlk satır sabit, %80 yakınlaştırma, yatay sayfa
 * - Hesap başlangıcı denetim için L kolonunda tutulur fakat varsayılan olarak gizlidir.
 */
object XlsxExporter {

    fun build(firma: String, tarih: String, results: List<VideoResult>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            add(zip, "[Content_Types].xml", contentTypes())
            add(zip, "_rels/.rels", rootRels())
            add(zip, "xl/workbook.xml", workbook())
            add(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            add(zip, "xl/styles.xml", styles())
            add(zip, "xl/worksheets/sheet1.xml", worksheet(firma, tarih, results))
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
  <sheets>
    <sheet name="Günlük Metraj" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""

    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="1">
    <numFmt numFmtId="164" formatCode="0.00"/>
  </numFmts>
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
    <border>
      <left style="thin"><color rgb="FFD7DEE3"/></left>
      <right style="thin"><color rgb="FFD7DEE3"/></right>
      <top style="thin"><color rgb="FFD7DEE3"/></top>
      <bottom style="thin"><color rgb="FFD7DEE3"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="7">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="left" vertical="center" shrinkToFit="1"/></xf>
    <xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
    <xf numFmtId="164" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
  </cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    private fun worksheet(firma: String, tarih: String, results: List<VideoResult>): String {
        val headers = listOf(
            "Tarih", "Firma", "Parsel", "Hat", "Çap", "Yön",
            "İlk (10.sn)", "Son", "Metraj", "Video", "Durum", "Hesap Başl."
        )

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\" zoomScale=\"80\" zoomScaleNormal=\"80\" showGridLines=\"0\">")
        sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
        sb.append("</sheetView></sheetViews>")
        sb.append("<sheetFormatPr defaultRowHeight=\"18\"/>")
        sb.append("<cols>")

        // Tablet ekranında gereksiz yatay uzamayı önlemek için sabit/dar kolonlar.
        val widths = listOf(10.5, 14.0, 8.0, 14.0, 7.0, 6.5, 10.0, 10.0, 10.5, 21.0, 14.0, 11.0)
        widths.forEachIndexed { index, width ->
            val hidden = if (index == 11) " hidden=\"1\"" else ""
            sb.append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"$hidden/>")
        }
        sb.append("</cols><sheetData>")

        var row = 1
        appendRow(sb, row++, headers.map { Cell.Text(it, STYLE_HEADER) }, height = 26.0)

        results.forEach { result ->
            appendRow(
                sb,
                row++,
                listOf(
                    Cell.Text(tarih, STYLE_TEXT),
                    Cell.Text(firma, STYLE_TEXT),
                    Cell.Text(result.header.parsel ?: "", STYLE_TEXT),
                    Cell.Text(result.header.konum ?: "", STYLE_TEXT),
                    result.header.capMm?.let { Cell.Number(it.toDouble(), STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT),
                    Cell.Text(result.header.yon ?: "", STYLE_TEXT),
                    result.firstMeter?.let { Cell.Number(it, STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT),
                    result.lastMeter?.let { Cell.Number(it, STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT),
                    result.metraj?.let { Cell.Number(it, STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT),
                    Cell.Text(result.fileName, STYLE_WRAP),
                    Cell.Text(result.error ?: "OK", STYLE_WRAP),
                    result.effectiveFirstMeter?.let { Cell.Number(it, STYLE_NUMBER) } ?: Cell.Text("", STYLE_TEXT)
                ),
                height = 20.0
            )
        }

        val total = results.mapNotNull { it.metraj }.sum()
        appendRow(
            sb,
            row,
            listOf(
                Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT),
                Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT),
                Cell.Text("", STYLE_TEXT), Cell.Text("TOPLAM", STYLE_TOTAL_LABEL), Cell.Number(total, STYLE_TOTAL_NUMBER),
                Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT), Cell.Text("", STYLE_TEXT)
            ),
            height = 22.0
        )

        val dataLastRow = maxOf(1, row - 1)
        sb.append("</sheetData>")
        sb.append("<autoFilter ref=\"A1:K$dataLastRow\"/>")
        sb.append("<pageMargins left=\"0.25\" right=\"0.25\" top=\"0.4\" bottom=\"0.4\" header=\"0.2\" footer=\"0.2\"/>")
        sb.append("<pageSetup orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
        sb.append("</worksheet>")
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

    private fun appendRow(
        sb: StringBuilder,
        rowNumber: Int,
        cells: List<Cell>,
        height: Double? = null
    ) {
        if (height != null) {
            sb.append("<row r=\"$rowNumber\" ht=\"$height\" customHeight=\"1\">")
        } else {
            sb.append("<row r=\"$rowNumber\">")
        }

        cells.forEachIndexed { index, cell ->
            val ref = "${columnName(index + 1)}$rowNumber"
            when (cell) {
                is Cell.Text -> {
                    sb.append("<c r=\"$ref\" s=\"${cell.style}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    sb.append(xmlEscape(cell.value))
                    sb.append("</t></is></c>")
                }
                is Cell.Number -> {
                    sb.append("<c r=\"$ref\" s=\"${cell.style}\"><v>${cell.value}</v></c>")
                }
            }
        }
        sb.append("</row>")
    }

    private fun columnName(number: Int): String {
        var n = number
        val sb = StringBuilder()
        while (n > 0) {
            n--
            sb.append(('A'.code + (n % 26)).toChar())
            n /= 26
        }
        return sb.reverse().toString()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
