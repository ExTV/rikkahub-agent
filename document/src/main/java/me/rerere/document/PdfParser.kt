package me.rerere.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

// Each page allocates a native fitz Page and StructuredText that only finalize() releases;
// without an explicit destroy() call these pile up as native memory for as long as this pass
// runs. MAX_PAGES also bounds a pathological or malicious PDF: a page-by-page prompt dump has
// no use past a few hundred pages anyway, since no LLM context window holds that much text.
private const val MAX_PAGES = 300

object PdfParser {
    fun parserPdf(file: File): String {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        try {
            val pageCount = document.countPages()
            val pagesToRead = minOf(pageCount, MAX_PAGES)
            val result = StringBuilder()
            for (i in 0 until pagesToRead) {
                val page = document.loadPage(i)
                try {
                    val structuredText = page.toStructuredText()
                    try {
                        result.append("---")
                        result.append("Page ${i + 1}:\n")
                        result.append(structuredText.asText())
                        result.appendLine()
                    } finally {
                        structuredText.destroy()
                    }
                } finally {
                    page.destroy()
                }
            }
            if (pagesToRead < pageCount) {
                result.append("\n[Truncated: showing $pagesToRead of $pageCount pages]")
            }
            return result.toString()
        } finally {
            document.destroy()
        }
    }
}
