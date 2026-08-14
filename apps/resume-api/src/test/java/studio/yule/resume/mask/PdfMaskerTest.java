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

class PdfMaskerTest {

    private final PdfMasker masker = new PdfMasker(new MaskProperties(null, null, null));

    /**
     * The whole point of the class. A black rectangle over a phone number is not
     * redaction if the glyphs survive in the content stream, so the assertion is
     * on what comes back out of the text extractor — not on what it looks like.
     */
    @Test
    void phoneNumberIsNotExtractableFromTheResult(@TempDir Path dir) throws IOException {
        Path source = write(dir, "Oh Yuchan", "Tel : 010-2483-0509", "Email : oyuchan50@gmail.com");

        PdfMasker.Result result = masker.mask(source, null);

        assertThat(result.hits()).isEqualTo(1);
        String text = textOf(result.pdf());
        assertThat(text).doesNotContain("010-2483-0509");
        assertThat(text).doesNotContain("2483");
    }

    /** Masking must not quietly eat the rest of the document. */
    @Test
    void everythingElseSurvives(@TempDir Path dir) throws IOException {
        Path source = write(dir, "Oh Yuchan", "Tel : 010-2483-0509", "Backend Engineer");

        PdfMasker.Result result = masker.mask(source, null);

        try (PDDocument doc = Loader.loadPDF(result.pdf())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
        // The page is a raster now, so the words are gone as text but present as
        // pixels. Size is the observable proxy: a blank page compresses to far
        // less than a page with a paragraph on it.
        assertThat(result.pdf().length).isGreaterThan(3_000);
    }

    /**
     * A résumé is full of numbers. If the pattern is loose enough to eat dates
     * and metrics, the delivered file is unreadable and nobody notices until a
     * recruiter opens it.
     */
    @Test
    void doesNotMatchTheNumbersAResumeIsMadeOf(@TempDir Path dir) throws IOException {
        Path source = write(dir, "2025.05 ~ 2026.06", "1,000 ~ 2,000 requests", "10 ~ 15 min");

        PdfMasker.Result result = masker.mask(source, null);

        assertThat(result.hits()).isZero();
    }

    @Test
    void findsPhoneNumbersWrittenSeveralWays(@TempDir Path dir) throws IOException {
        Path source = write(dir, "010-2483-0509", "010 2483 0509", "+82 10-2483-0509");

        PdfMasker.Result result = masker.mask(source, null);

        assertThat(result.hits()).isEqualTo(3);
        assertThat(textOf(result.pdf())).doesNotContain("2483");
    }

    /** The serial has to end up on the page, which is why it is drawn at all. */
    @Test
    void stampIsDrawnWithoutAddingATextLayer(@TempDir Path dir) throws IOException {
        Path source = write(dir, "Tel : 010-2483-0509");

        PdfMasker.Result stamped = masker.mask(source, "발급 #42 · 2026-08-14 · 홍길동");
        PdfMasker.Result plain = masker.mask(source, null);

        // Drawn as pixels: the file grows, but nothing is extractable.
        assertThat(stamped.pdf().length).isGreaterThan(plain.pdf().length);
        assertThat(textOf(stamped.pdf())).doesNotContain("42");
    }

    /**
     * Only the page with the match is rasterised. This is the difference
     * between a two-second download and a forty-second one — and it is
     * observable, so it is pinned here rather than left to drift back.
     */
    @Test
    void pagesWithNoMatchKeepTheirText(@TempDir Path dir) throws IOException {
        Path source = writePages(dir,
                new String[]{"Tel : 010-2483-0509"},
                new String[]{"EXPERIENCE", "Backend Engineer at ACME"});

        PdfMasker.Result result = masker.mask(source, null);

        assertThat(result.hits()).isEqualTo(1);
        assertThat(pageText(result.pdf(), 1)).doesNotContain("2483");
        // untouched, so it is still selectable text rather than a picture of it
        assertThat(pageText(result.pdf(), 2)).contains("Backend Engineer at ACME");
    }

    /**
     * The masked page must not keep a text layer — that is the whole guarantee,
     * and a shape drawn over live glyphs would still pass a visual check.
     */
    @Test
    void theMaskedPageHasNoTextLayerAtAll(@TempDir Path dir) throws IOException {
        Path source = writePages(dir, new String[]{"Oh Yuchan", "Tel : 010-2483-0509"});

        PdfMasker.Result result = masker.mask(source, null);

        assertThat(pageText(result.pdf(), 1).replaceAll("\\s", "")).isEmpty();
    }

    /**
     * Runs against the real file when it is where the dev setup puts it, and
     * skips otherwise so the suite stays green on a clean checkout.
     */
    @Test
    void masksTheActualResumeWhenPresent() throws IOException {
        Path resume = Path.of("../../../hompage/private/resume.pdf").normalize();
        assumeTrue(Files.isReadable(resume), "source résumé not present");

        PdfMasker.Result result = masker.mask(resume, "발급 #1 · 2026-08-14 · 테스트");

        assertThat(result.hits()).isGreaterThan(0);
        assertThat(textOf(result.pdf())).doesNotContain("2483");
    }

    /* ── helpers ────────────────────────────────────────────── */

    private Path write(Path dir, String... lines) throws IOException {
        return writePages(dir, lines);
    }

    /** One String[] per page. */
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

    /** 1-based, like the stripper's own page numbering. */
    private String pageText(byte[] pdf, int page) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(doc);
        }
    }
}
