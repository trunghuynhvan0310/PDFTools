package com.sammyi57.pdftools

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val COLUMNS = listOf(
  "STT" to 6, "Số và ký hiệu" to 20, "Ngày văn bản" to 14, "Cơ quan ban hành" to 34, "Loại" to 14, "Tiêu đề (trích yếu)" to 50,
  "Kính gửi" to 34, "Nơi nhận" to 40, "Người ký" to 24, "Chức vụ" to 24, "Số trang" to 9, "Tệp PDF" to 30,
)

private fun xml(s: String) = buildString(s.length) {
  for (ch in s) when {
    ch == '&' -> append("&amp;")
    ch == '<' -> append("&lt;")
    ch == '>' -> append("&gt;")
    ch == '"' -> append("&quot;")
    ch == '\n' || ch == '\t' -> append(ch)
    ch.code < 32 -> {} // control characters are not allowed in XML
    else -> append(ch)
  }
}

private fun colName(i: Int) = ('A' + i).toString()

/** One spreadsheet row, already in the order and form it is written. */
data class ExportRow(
  val soKyHieu: String, val date: LocalDate?, val dateText: String, val coQuan: String, val loai: String, val trichYeu: String,
  val kinhGui: String, val noiNhan: String, val nguoiKy: String, val chucVu: String, val pages: Int, val pdfName: String,
)

fun xlsxOf(rows: List<ExportRow>): ByteArray {
  val sheet = StringBuilder()
  sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
  sheet.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
  sheet.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
  sheet.append("<cols>")
  COLUMNS.forEachIndexed { i, (_, w) -> sheet.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
  sheet.append("</cols><sheetData>")

  fun text(ref: String, s: String, style: Int) {
    if (s.isEmpty()) return
    sheet.append("""<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${xml(s)}</t></is></c>""")
  }
  fun number(ref: String, n: Number, style: Int) { sheet.append("""<c r="$ref" s="$style"><v>$n</v></c>""") }

  sheet.append("""<row r="1" ht="22" customHeight="1">""")
  COLUMNS.forEachIndexed { i, (name, _) -> text("${colName(i)}1", name, 1) }
  sheet.append("</row>")
  rows.forEachIndexed { n, v ->
    val r = n + 2
    sheet.append("""<row r="$r">""")
    number("A$r", n + 1, 0)
    text("B$r", v.soKyHieu, 0)
    val d = v.date
    if (d != null) number("C$r", d.toEpochDay() + 25569, 2) else text("C$r", v.dateText, 0)
    text("D$r", v.coQuan, 0); text("E$r", v.loai, 0); text("F$r", v.trichYeu, 0)
    text("G$r", v.kinhGui, 0); text("H$r", v.noiNhan, 0); text("I$r", v.nguoiKy, 0); text("J$r", v.chucVu, 0)
    if (v.pages > 0) number("K$r", v.pages, 0)
    text("L$r", v.pdfName, 0)
    sheet.append("</row>")
  }
  sheet.append("</sheetData>")
  sheet.append("""<autoFilter ref="A1:${colName(COLUMNS.size - 1)}${rows.size + 1}"/>""")
  sheet.append("</worksheet>")

  val parts = linkedMapOf(
    "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
    "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
    "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sổ công văn" sheetId="1" r:id="rId1"/></sheets></workbook>""",
    "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
    // 0 plain wrapped text, 1 bold header on a tint, 2 a date.
    "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE6EFE9"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="14" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment horizontal="left" vertical="top"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""",
    "xl/worksheets/sheet1.xml" to sheet.toString(),
  )
  return ByteArrayOutputStream().use { bos ->
    ZipOutputStream(bos).use { z ->
      for ((name, body) in parts) {
        z.putNextEntry(ZipEntry(name))
        z.write(body.toByteArray(Charsets.UTF_8))
        z.closeEntry()
      }
    }
    bos.toByteArray()
  }
}
