package com.sammyi57.pdftools

import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.text.Normalizer

/**
 * The font that text written onto a PDF page uses (stamps, page numbers, signatures, the OCR text layer).
 * Be Vietnam Pro is embedded as a subset, so every Vietnamese letter and tone mark survives, and the PDF still opens
 * the same on a phone that does not have the font. Widths come from the same file, so editor previews match.
 */
object PdfFonts {
  enum class Face(val res: Int) { Regular(R.font.be_vietnam_regular), Bold(R.font.be_vietnam_semibold) }

  private val parsed = HashMap<Face, TrueTypeFont>()

  private fun ttf(face: Face): TrueTypeFont = synchronized(parsed) {
    parsed.getOrPut(face) { appContext.resources.openRawResource(face.res).use { TTFParser().parse(it) } }
  }

  /** A font object for this document, embedded as a subset when the document is saved. */
  fun load(doc: PDDocument, face: Face = Face.Regular): PDFont {
    val bytes = appContext.resources.openRawResource(face.res).use { it.readBytes() }
    return PDType0Font.load(doc, bytes.inputStream(), true)
  }

  private fun hasGlyph(t: TrueTypeFont, cp: Int): Boolean = runCatching { t.unicodeCmapLookup.getGlyphId(cp) > 0 }.getOrDefault(false)

  /**
   * What the page will really say: composed (NFC) so a keyboard that types a letter and its tone mark separately still
   * lands on the single glyph, with curly punctuation kept, and anything the font cannot draw left out.
   */
  fun clean(s: String, face: Face = Face.Regular): String {
    val t = ttf(face)
    val sb = StringBuilder()
    Normalizer.normalize(s, Normalizer.Form.NFC).codePoints().forEach { cp ->
      val ok = cp >= 0x20 && cp != 0x7F && !Character.isISOControl(cp) && hasGlyph(t, cp)
      if (ok) sb.appendCodePoint(cp)
    }
    return sb.toString()
  }

  /** The text's width in 1/1000 em (what PDFont.getStringWidth returns), measured without a document. */
  fun width(text: String, face: Face = Face.Regular): Float {
    val t = ttf(face)
    val units = t.unitsPerEm.toFloat()
    var w = 0f
    text.codePoints().forEach { cp -> w += t.getAdvanceWidth(t.unicodeCmapLookup.getGlyphId(cp)) / units * 1000f }
    return w
  }
}
