package studio.yule.resume.mask;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Marks the copy of the résumé that leaves the server.
 *
 * <p>This used to redact the phone number as well, by rasterising the page it
 * appeared on. That was dropped: the résumé only goes to someone who has given
 * their name, and every copy is logged and numbered, so the contact details are
 * the point rather than the risk. Removing it took the whole render step out —
 * a download is now a copy plus a stamp, and every page keeps its text layer.
 *
 * <p>What remains is the serial. It is drawn on each page so a copy found
 * somewhere it should not be points back at one row in the download log.
 */
@Component
public class PdfStamper {

    /** Only used to size the stamp bitmap — nothing else is rendered. */
    private static final float SCALE = 150f / 72f;

    /**
     * @param source the résumé as authored
     * @param stamp  a line drawn at the foot of every page, or null
     */
    public byte[] stamp(Path source, String stamp) throws IOException {
        try (PDDocument doc = Loader.loadPDF(source.toFile())) {
            if (stamp != null && !stamp.isBlank()) {
                stampEveryPage(doc, stamp);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            doc.save(bytes);
            return bytes.toByteArray();
        }
    }

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
}
