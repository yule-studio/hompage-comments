package studio.yule.resume.mask;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Produces the copy of the résumé that leaves the server.
 *
 * <h2>Why the matched page is rasterised</h2>
 * The obvious implementation — draw a shape over the phone number — does not
 * redact anything. The glyphs stay in the content stream underneath, so the
 * number comes straight back out of copy-paste, {@code pdftotext}, or any PDF
 * library. That failure mode is the reason redaction makes the news.
 *
 * So a page that contains a match is re-drawn as an image and its content
 * stream is replaced. What is removed is removed because the text that drew it
 * no longer exists — there is nothing left to extract.
 *
 * <h2>Why only that page</h2>
 * Rasterising is by far the most expensive thing here: on the current résumé
 * one page costs twelve seconds on its own. Pages with no match have nothing to
 * hide, so they are passed through untouched — which is both faster and better,
 * since they keep their vector text and stay selectable.
 *
 * <h2>Why the cover is not a blur of the real glyphs</h2>
 * See {@link #hide} — blurring a phone number in place is reversible, so the
 * pixels are destroyed first and the soft panel is drawn over the hole.
 */
@Component
public class PdfMasker {

    /** Rendering resolution for a masked page. 150 stays sharp on screen. */
    private static final float DPI = 150f;
    private static final float SCALE = DPI / 72f;
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
        /*
         * Edited in place and saved to a byte array. The source file on disk is
         * never written back to, and building a second document just to copy
         * pages into costs more than it buys.
         */
        try (PDDocument doc = Loader.loadPDF(source.toFile())) {
            Map<Integer, List<Box>> found = locate(doc);
            int hits = found.values().stream().mapToInt(List::size).sum();

            PDFRenderer renderer = new PDFRenderer(doc);
            for (Map.Entry<Integer, List<Box>> page : found.entrySet()) {
                redact(doc, renderer, page.getKey(), page.getValue());
            }

            if (stamp != null && !stamp.isBlank()) {
                stampEveryPage(doc, stamp);
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return new Result(bytes.toByteArray(), hits);
        }
    }

    /* ── locate ─────────────────────────────────────────────── */

    /** A rectangle to cover, in PDF points with the origin at the top left. */
    private record Box(float x, float y, float width, float height) {}

    /**
     * One pass over the whole document rather than one per page — the stripper
     * re-parses everything it is given, and five passes cost five times as much
     * for the same answer.
     */
    private Map<Integer, List<Box>> locate(PDDocument doc) throws IOException {
        PositionedText text = new PositionedText();
        text.setStartPage(1);
        text.setEndPage(doc.getNumberOfPages());
        text.getText(doc);
        return text.matches(patterns);
    }

    /* ── redact ─────────────────────────────────────────────── */

    /**
     * Replaces the page with an image of itself, minus the located text. The
     * original content stream goes with it: {@link AppendMode#OVERWRITE} swaps
     * the reference, and a full save never writes the orphan.
     */
    private void redact(PDDocument doc, PDFRenderer renderer, int index, List<Box> boxes)
            throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(index, DPI);
        for (Box b : boxes) {
            hide(image, toPixels(b));
        }

        PDPage page = doc.getPage(index);
        PDRectangle size = page.getCropBox();

        // Drop what the old content referenced — embedded fonts on a page that
        // no longer draws text are dead weight, and an annotation could carry a
        // tel: URI the pixels no longer show.
        page.setResources(new PDResources());
        page.setAnnotations(List.of());

        PDImageXObject xobject = JPEGFactory.createFromImage(doc, image, JPEG_QUALITY);
        try (PDPageContentStream content =
                     new PDPageContentStream(doc, page, AppendMode.OVERWRITE, true, true)) {
            content.drawImage(xobject, size.getLowerLeftX(), size.getLowerLeftY(),
                    size.getWidth(), size.getHeight());
        }
    }

    private Rectangle toPixels(Box b) {
        return new Rectangle(
                Math.round((b.x() - PAD) * SCALE),
                Math.round((b.y() - PAD) * SCALE),
                Math.round((b.width() + PAD * 2) * SCALE),
                Math.round((b.height() + PAD * 2) * SCALE));
    }

    /**
     * Erase, then cover.
     *
     * <p>A blur over the real glyphs would look the same and would not be safe.
     * A phone number is eight digits from an alphabet of ten in a known font —
     * small enough that an attacker can render every candidate, blur each one
     * the same way, and keep the one that matches. Published tools do exactly
     * this to defeat blurred and pixelated redactions.
     *
     * <p>So the pixels are destroyed first: the region is flooded with the
     * background sampled from beside it, and only then is the panel drawn and
     * softened. By the time anything is blurred, there is nothing underneath it
     * but flat colour — the soft edge is decoration, not the protection.
     */
    private void hide(BufferedImage image, Rectangle r) {
        Rectangle box = r.intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        if (box.isEmpty()) {
            return;
        }

        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 1. erase — everything the glyphs drew is gone from here on
        g.setColor(sampleBackground(image, box));
        g.fillRect(box.x, box.y, box.width, box.height);

        // 2. cover — a soft bar, so it reads as "hidden" rather than "missing"
        int radius = Math.max(3, box.height / 3);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, props.opacity()));
        g.setColor(props.fill());
        g.fillRoundRect(box.x, box.y, box.width, box.height, radius, radius);
        g.dispose();

        // 3. soften — the region is synthetic now, so this leaks nothing
        blur(image, box);
    }

    /**
     * The colour just outside the box, so the hole matches the paper instead of
     * punching white through a tinted panel.
     */
    private Color sampleBackground(BufferedImage image, Rectangle box) {
        int y = Math.clamp(box.y + box.height / 2, 0, image.getHeight() - 1);
        int x = Math.clamp(box.x - Math.max(4, box.height / 2), 0, image.getWidth() - 1);
        return new Color(image.getRGB(x, y));
    }

    /**
     * Gaussian blur confined to the box. Pixels around it are read so the edge
     * blends, but only the box itself is written back — neighbouring text stays
     * as sharp as it was.
     */
    private void blur(BufferedImage image, Rectangle box) {
        int radius = Math.clamp(box.height / 6, 2, 8);
        int size = radius * 2 + 1;

        float[] kernel = new float[size * size];
        float sigma = radius / 2f;
        float sum = 0f;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                float v = (float) Math.exp(-(x * x + y * y) / (2 * sigma * sigma));
                kernel[(y + radius) * size + (x + radius)] = v;
                sum += v;
            }
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }

        Rectangle source = new Rectangle(
                box.x - radius, box.y - radius, box.width + radius * 2, box.height + radius * 2)
                .intersection(new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        if (source.isEmpty()) {
            return;
        }

        BufferedImage patch = new BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = patch.createGraphics();
        pg.drawImage(image, -source.x, -source.y, null);
        pg.dispose();

        BufferedImage blurred =
                new ConvolveOp(new Kernel(size, size, kernel), ConvolveOp.EDGE_NO_OP, null)
                        .filter(patch, null);

        Graphics2D g = image.createGraphics();
        g.setClip(box);
        g.drawImage(blurred, source.x, source.y, null);
        g.dispose();
    }

    /* ── stamp ──────────────────────────────────────────────── */

    /**
     * Rendered once to a small image and drawn on every page. Drawing it as
     * text would mean embedding a font that covers Korean names; this is a few
     * kilobytes and reuses one XObject across the document.
     */
    private void stampEveryPage(PDDocument doc, String line) throws IOException {
        BufferedImage rendered = renderStamp(line);
        PDImageXObject xobject = LosslessFactory.createFromImage(doc, rendered);

        for (PDPage page : doc.getPages()) {
            PDRectangle size = page.getCropBox();
            float width = rendered.getWidth() / SCALE;
            float height = rendered.getHeight() / SCALE;
            float margin = size.getWidth() / 40f;

            try (PDPageContentStream content =
                         new PDPageContentStream(doc, page, AppendMode.APPEND, true, true)) {
                // bottom left: page numbers in this layout sit on the right,
                // and overprinting them makes both unreadable
                content.drawImage(xobject,
                        size.getLowerLeftX() + margin,
                        size.getLowerLeftY() + margin,
                        width, height);
            }
        }
    }

    private BufferedImage renderStamp(String line) {
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(7f * SCALE));

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        int width = pg.getFontMetrics().stringWidth(line);
        int height = pg.getFontMetrics().getHeight();
        pg.dispose();

        BufferedImage out = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(new Color(0x88, 0x88, 0x88));
        g.drawString(line, 0, g.getFontMetrics().getAscent());
        g.dispose();
        return out;
    }

    /* ── text with coordinates ──────────────────────────────── */

    /**
     * PDFBox hands text to {@code writeString} in chunks with one
     * {@link org.apache.pdfbox.text.TextPosition} per rendered glyph. Keeping a
     * character-aligned index alongside the string lets a regex match over the
     * readable text be translated back into rectangles on the page.
     */
    private static final class PositionedText extends org.apache.pdfbox.text.PDFTextStripper {

        private final Map<Integer, StringBuilder> text = new HashMap<>();
        private final Map<Integer, List<org.apache.pdfbox.text.TextPosition>> index = new HashMap<>();

        private PositionedText() throws IOException {
            super();
            setSortByPosition(true);
        }

        /** 0-based, matching PDDocument.getPage. */
        private int page() {
            return getCurrentPageNo() - 1;
        }

        @Override
        protected void writeString(String string,
                                   List<org.apache.pdfbox.text.TextPosition> positions) {
            StringBuilder sb = text.computeIfAbsent(page(), k -> new StringBuilder());
            List<org.apache.pdfbox.text.TextPosition> idx =
                    index.computeIfAbsent(page(), k -> new ArrayList<>());

            for (org.apache.pdfbox.text.TextPosition p : positions) {
                String unicode = p.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                // A ligature is one TextPosition but several characters; repeat
                // it so idx.get(i) is always the glyph that drew text[i].
                sb.append(unicode);
                for (int i = 0; i < unicode.length(); i++) {
                    idx.add(p);
                }
            }
            sb.append('\n');
            idx.add(null);
        }

        @Override
        protected void writeLineSeparator() {
            // handled in writeString so the index stays aligned
        }

        @Override
        protected void writeWordSeparator() {
            text.computeIfAbsent(page(), k -> new StringBuilder()).append(' ');
            index.computeIfAbsent(page(), k -> new ArrayList<>()).add(null);
        }

        /** Pages with at least one match, in page order. */
        Map<Integer, List<Box>> matches(List<Pattern> patterns) {
            Map<Integer, List<Box>> byPage = new java.util.TreeMap<>();

            for (Map.Entry<Integer, StringBuilder> entry : text.entrySet()) {
                int page = entry.getKey();
                String haystack = entry.getValue().toString();
                List<org.apache.pdfbox.text.TextPosition> idx = index.get(page);
                List<Box> boxes = new ArrayList<>();

                for (Pattern pattern : patterns) {
                    Matcher m = pattern.matcher(haystack);
                    while (m.find()) {
                        Box box = boundingBox(idx, m.start(), m.end());
                        if (box != null) {
                            boxes.add(box);
                        }
                    }
                }
                if (!boxes.isEmpty()) {
                    byPage.put(page, boxes);
                }
            }
            return byPage;
        }

        /** Union of the glyphs behind [start, end) — null if none were real. */
        private Box boundingBox(List<org.apache.pdfbox.text.TextPosition> idx, int start, int end) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            boolean found = false;

            for (int i = start; i < end && i < idx.size(); i++) {
                org.apache.pdfbox.text.TextPosition p = idx.get(i);
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
