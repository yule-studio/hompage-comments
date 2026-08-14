package studio.yule.resume.mask;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.awt.Color;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What gets hidden, and what the cover looks like.
 *
 * The patterns are configuration rather than constants because what counts as
 * private depends on who is asking. The default hides the phone number and
 * leaves the email — a résumé nobody can reply to is not much of a résumé, and
 * the address is already published on the site.
 */
@ConfigurationProperties(prefix = "resume.mask")
public record MaskProperties(List<String> patterns, String fillColor) {

    /**
     * Korean mobile and landline numbers, separated by hyphen, space, dot or
     * nothing. Two shapes, because the international form drops the leading
     * zero the domestic one requires: {@code 010-2483-0509} and
     * {@code +82 10-2483-0509}.
     *
     * The leading group is anchored to that zero (or the country code) and the
     * trailing group to four digits, which is what keeps the pattern off the
     * numbers a résumé is actually made of — "1,000 ~ 2,000건", "2025.05.13",
     * "10 ~ 15분".
     */
    private static final String KR_PHONE =
            "(?:\\+?82[-\\s.]?1[0-9]|0\\d{1,2})[-\\s.]?\\d{3,4}[-\\s.]?\\d{4}";

    public MaskProperties {
        if (patterns == null || patterns.isEmpty()) {
            patterns = List.of(KR_PHONE);
        }
        if (fillColor == null || fillColor.isBlank()) {
            fillColor = "#111111";
        }
    }

    public List<Pattern> compiled() {
        return patterns.stream()
                .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    public Color fill() {
        return Color.decode(fillColor);
    }
}
