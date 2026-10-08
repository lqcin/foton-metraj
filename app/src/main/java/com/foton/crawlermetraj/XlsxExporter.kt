package com.foton.crawlermetraj

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxExporter {

    fun build(firma: String, tarih: String, results: List<VideoResult>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            add(zip, "[Content_Types].xml", contentTypes())
            add(zip, "_rels/.rels", rootRels())
            add(zip, "xl/workbook.xml", workbook())
            add(zip, "xl/_rels/workbook.xml.rels", workbookRels())
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
</Relationships>"""

    private fun worksheet(firma: String, tarih: String, results: List<VideoResult>): String {
        val headers = listOf(
            "Tarih", "Firma", "Parsel", "Hat", "Çap (mm)", "Yön",
            "10.sn Ham Sayaç (m)", "Hesap Başlangıcı (m)", "Son Sayaç (m)", "Metraj (m)", "Video", "Durum"
        )

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<cols>")
        val widths = listOf(13, 18, 12, 18, 12, 10, 18, 20, 15, 14, 35, 28)
        widths.forEachIndexed { index, width ->
            sb.append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
        }
        sb.append("</cols><sheetData>")

        var row = 1
        appendRow(sb, row++, headers.map { Cell.Text(it) })

        results.forEach { result ->
            appendRow(
                sb,
                row++,
                listOf(
                    Cell.Text(tarih),
                    Cell.Text(firma),
                    Cell.Text(result.header.parsel ?: ""),
                    Cell.Text(result.header.konum ?: ""),
                    result.header.capMm?.let { Cell.Number(it.toDouble()) } ?: Cell.Text(""),
                    Cell.Text(result.header.yon ?: ""),
                    result.firstMeter?.let { Cell.Number(it) } ?: Cell.Text(""),
                    result.effectiveFirstMeter?.let { Cell.Number(it) } ?: Cell.Text(""),
                    result.lastMeter?.let { Cell.Number(it) } ?: Cell.Text(""),
                    result.metraj?.let { Cell.Number(it) } ?: Cell.Text(""),
                    Cell.Text(result.fileName),
                    Cell.Text(result.error ?: "OK")
                )
            )
        }

        val total = results.mapNotNull { it.metraj }.sum()
        appendRow(
            sb,
            row,
            listOf(
                Cell.Text(""), Cell.Text(""), Cell.Text(""), Cell.Text(""), Cell.Text(""),
                Cell.Text(""), Cell.Text(""), Cell.Text(""), Cell.Text("TOPLAM"), Cell.Number(total),
                Cell.Text(""), Cell.Text("")
            )
        )

        sb.append("</sheetData><autoFilter ref=\"A1:L${maxOf(1, row - 1)}\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private sealed class Cell {
        data class Text(val value: String) : Cell()
        data class Number(val value: Double) : Cell()
    }

    private fun appendRow(sb: StringBuilder, rowNumber: Int, cells: List<Cell>) {
        sb.append("<row r=\"$rowNumber\">")
        cells.forEachIndexed { index, cell ->
            val ref = "${columnName(index + 1)}$rowNumber"
            when (cell) {
                is Cell.Text -> {
                    sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>")
                    sb.append(xmlEscape(cell.value))
                    sb.append("</t></is></c>")
                }
                is Cell.Number -> {
                    sb.append("<c r=\"$ref\"><v>${cell.value}</v></c>")
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
