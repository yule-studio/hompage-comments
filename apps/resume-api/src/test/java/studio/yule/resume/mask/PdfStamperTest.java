package studio.yule.resume.mask;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfStamperTest {

    private final PdfStamper stamper = new PdfStamper();

    /**
     * The serial is the only reason this class exists — a copy found in the
     * wrong place has to point back at one row of the download log.
     */
    @Test
    void stampGrowsTheFileAndLeavesTheDocumentIntact(@TempDir Path dir) throws IOException {
        Path source = writePages(dir, new String[]{"Oh Yuchan", "Backend Engineer"});

        byte[] stamped = stamper.stamp(source, "발급 #42 · 2026-08-16 · 홍길동");
        byte[] plain = stamper.stamp(source, null);

        assertThat(stamped.length).isGreaterThan(plain.length);
        assertThat(textOf(stamped)).contains("Backend Engineer");
    }

    /**
     * Pages are copied, not re-rendered. Text has to survive on every one of
     * them — that is what dropping the redaction step bought.
     */
    @Test
    void everyPageKeepsItsTextLayer(@TempDir Path dir) throws IOException {
        Path source = writePages(dir,
                new String[]{"Tel : 010-2483-0509"},
                new String[]{"EXPERIENCE", "Backend Engineer at ACME"});

        byte[] stamped = stamper.stamp(source, "발급 #7 · 2026-08-16 · 테스트");

        assertThat(pageText(stamped, 1)).contains("010-2483-0509");
        assertThat(pageText(stamped, 2)).contains("Backend Engineer at ACME");
    }

    @Test
    void pageCountIsUnchanged(@TempDir Path dir) throws IOException {
        Path source = writePages(dir, new String[]{"one"}, new String[]{"two"}, new String[]{"three"});

        try (PDDocument doc = Loader.loadPDF(stamper.stamp(source, "발급 #1 · 2026-08-16 · 테스트"))) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
        }
    }

    @Test
    void nullStampIsAPlainCopy(@TempDir Path dir) throws IOException {
        Path source = writePages(dir, new String[]{"Oh Yuchan"});

        assertThat(textOf(stamper.stamp(source, null))).contains("Oh Yuchan");
        assertThat(textOf(stamper.stamp(source, "   "))).contains("Oh Yuchan");
    }

    /** Runs against the real file when the dev setup has it; skips otherwise. */
    @Test
    void stampsTheActualResumeWhenPresent() throws IOException {
        Path resume = Path.of("../../../hompage/private/resume.pdf").normalize();
        assumeTrue(Files.isReadable(resume), "source résumé not present");

        byte[] stamped = stamper.stamp(resume, "발급 #1 · 2026-08-16 · 테스트");

        try (PDDocument doc = Loader.loadPDF(stamped)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(0);
        }
    }

    /* ── helpers ────────────────────────────────────────────── */

    private Path writePages(Path dir, String[]... pages) throws IOException {
        Path file = dir.resolve("source.pdf");
        try (PDDocument doc = new PDDocument()) {
            for (String[] lines : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.setLeading(18f);
                    content.newLineAtOffset(60, 700);
                    for (String line : List.of(lines)) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private String textOf(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String pageText(byte[] pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(doc);
        }
    }
}
