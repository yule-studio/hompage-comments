package studio.yule.resume.mask;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces the copy of the résumé that leaves the server.
 *
 * <h2>Why this rasterises</h2>
 * The obvious implementation — draw a black rectangle over the phone number —
 * does not redact anything. The glyphs stay in the content stream underneath,
 * so the number comes straight back out of copy-paste, {@code pdftotext}, or
 * any PDF library. That failure mode is the reason redaction makes the news.
 *
 * So each page is located, then rendered to an image, then covered, and the
 * image becomes the page. What is removed is removed because the text layer no
 * longer exists at all — there is nothing left to extract. The trade is real:
 * the delivered file has no selectable text and is larger than the source.
 * For a résumé handed to someone who just identified themselves, that is the
 * right side of the trade.
 *
 * <h2>What it does not do</h2>
 * Matching is per page over the text as {@link PDFTextStripper} orders it. If a
 * phone number is split across two lines, or drawn as part of an image, it will
 * not be found — {@link #mask} reports how many hits it made so the caller can
 * refuse to serve a file that masked nothing.
 */
@Component
public class PdfMasker {

    /** Rendering resolution. 150 stays sharp on screen without a huge file. */
    private static final float DPI = 150f;
    private static final float JPEG_QUALITY = 0.85f;
    /** Padding around a located box, in PDF points, so glyph edges are covered. */
    private static final float PAD = 1.5f;

    private final MaskProperties props;
    private final List<Pattern> patterns;

    public PdfMasker(MaskProperties props) {
        this.props = props;
        this.patterns = props.compiled();
    }

    /** The masked document, plus how many pieces of text were covered. */
    public record Result(byte[] pdf, int hits) {}

    /**
     * @param source the résumé as authored — never served directly
     * @param stamp  a line drawn at the foot of every page, or null. Carries the
     *               issue serial so a leaked copy points back at one download.
     */
    public Result mask(Path source, String stamp) throws IOException {
        try (PDDocument in = Loader.loadPDF(source.toFile());
             PDDocument out = new PDDocument()) {

            PDFRenderer renderer = new PDFRenderer(in);
            int hits = 0;

            for (int i = 0; i < in.getNumberOfPages(); i++) {
                List<Box> boxes = locate(in, i);
                hits += boxes.size();

                BufferedImage page = renderer.renderImageWithDPI(i, DPI);
                cover(page, boxes);
                if (stamp != null && !stamp.isBlank()) {
                    stamp(page, stamp);
                }

                append(out, page, in.getPage(i).getMediaBox());
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            return new Result(bytes.toByteArray(), hits);
        }
    }

    /* ── locate ─────────────────────────────────────────────── */

    /** A rectangle to cover, in PDF points with the origin at the top left. */
    private record Box(float x, float y, float width, float height) {}

    private List<Box> locate(PDDocument doc, int pageIndex) throws IOException {
        PositionedText text = new PositionedText();
        text.setStartPage(pageIndex + 1);
        text.setEndPage(pageIndex + 1);
        text.getText(doc);

        return text.matches(patterns);
    }

    /* ── cover ──────────────────────────────────────────────── */

    private void cover(BufferedImage image, List<Box> boxes) {
        if (boxes.isEmpty()) {
            return;
        }
        float scale = DPI / 72f;
        Graphics2D g = image.createGraphics();
        g.setColor(props.fill());
        for (Box b : boxes) {
            g.fillRect(
                    Math.round((b.x() - PAD) * scale),
                    Math.round((b.y() - PAD) * scale),
                    Math.round((b.width() + PAD * 2) * scale),
                    Math.round((b.height() + PAD * 2) * scale));
        }
        g.dispose();
    }

    /**
     * Drawn onto the raster rather than written as PDF text, which sidesteps the
     * standard-14 fonts having no Korean glyphs — and keeps the promise that the
     * delivered file carries no text layer.
     */
    private void stamp(BufferedImage image, String line) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int size = Math.max(9, Math.round(image.getWidth() / 92f));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size));
        g.setColor(new Color(0x99, 0x99, 0x99));

        // Bottom left: page numbers and footers in this layout sit on the right,
        // and overprinting them makes both unreadable.
        int margin = Math.round(image.getWidth() / 40f);
        g.drawString(line, margin, image.getHeight() - margin);
        g.dispose();
    }

    /* ── assemble ───────────────────────────────────────────── */

    private void append(PDDocument out, BufferedImage image, PDRectangle size) throws IOException {
        PDPage page = new PDPage(new PDRectangle(size.getWidth(), size.getHeight()));
        out.addPage(page);

        PDImageXObject xobject = JPEGFactory.createFromImage(out, image, JPEG_QUALITY);
        try (PDPageContentStream content = new PDPageContentStream(out, page)) {
            content.drawImage(xobject, 0, 0, size.getWidth(), size.getHeight());
        }
    }

    /* ── text with coordinates ──────────────────────────────── */

    /**
     * PDFBox hands text to {@code writeString} in chunks with one
     * {@link TextPosition} per rendered glyph. Keeping a character-aligned
     * index alongside the string lets a regex match over the readable text be
     * translated back into rectangles on the page.
     */
    private static final class PositionedText extends org.apache.pdfbox.text.PDFTextStripper {

        private final StringBuilder text = new StringBuilder();
        private final java.util.List<org.apache.pdfbox.text.TextPosition> index = new java.util.ArrayList<>();

        private PositionedText() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String string,
                                   java.util.List<org.apache.pdfbox.text.TextPosition> positions) {
            for (org.apache.pdfbox.text.TextPosition p : positions) {
                String unicode = p.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                // A ligature is one TextPosition but several characters; repeat
                // it so index.get(i) is always the glyph that drew text[i].
                text.append(unicode);
                for (int i = 0; i < unicode.length(); i++) {
                    index.add(p);
                }
            }
            text.append('\n');
            index.add(null);
        }

        @Override
        protected void writeLineSeparator() {
            // handled in writeString so the index stays aligned
        }

        @Override
        protected void writeWordSeparator() {
            text.append(' ');
            index.add(null);
        }

        java.util.List<Box> matches(List<Pattern> patterns) {
            java.util.List<Box> boxes = new java.util.ArrayList<>();
            String haystack = text.toString();

            for (Pattern pattern : patterns) {
                Matcher m = pattern.matcher(haystack);
                while (m.find()) {
                    Box box = boundingBox(m.start(), m.end());
                    if (box != null) {
                        boxes.add(box);
                    }
                }
            }
            return boxes;
        }

        /** Union of the glyphs behind [start, end) — null if none were real. */
        private Box boundingBox(int start, int end) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            boolean found = false;

            for (int i = start; i < end && i < index.size(); i++) {
                org.apache.pdfbox.text.TextPosition p = index.get(i);
                if (p == null) {
                    continue;
                }
                found = true;
                minX = Math.min(minX, p.getXDirAdj());
                minY = Math.min(minY, p.getYDirAdj() - p.getHeightDir());
                maxX = Math.max(maxX, p.getXDirAdj() + p.getWidthDirAdj());
                maxY = Math.max(maxY, p.getYDirAdj());
            }
            return found ? new Box(minX, minY, maxX - minX, maxY - minY) : null;
        }
    }
}
