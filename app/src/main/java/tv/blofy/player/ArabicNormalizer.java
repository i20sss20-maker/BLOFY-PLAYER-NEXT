package tv.blofy.player;

import java.text.Normalizer;
import java.util.Locale;

/** Pure-Java Arabic text folding used by instant catalog search. */
final class ArabicNormalizer {
    private ArabicNormalizer() {}

    static String normalizeForSearch(String value) {
        if (value == null || value.trim().isEmpty()) return "";

        String compatible = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(compatible.length());
        boolean pendingSpace = false;

        for (int offset = 0; offset < compatible.length(); ) {
            int codePoint = compatible.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (codePoint == 0x0640 || isArabicMark(codePoint)
                    || isCombiningMark(Character.getType(codePoint))) {
                continue;
            }
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = result.length() > 0;
                continue;
            }
            if (pendingSpace) {
                result.append(' ');
                pendingSpace = false;
            }
            result.appendCodePoint(fold(codePoint));
        }
        return result.toString();
    }

    static String firstLetterKey(String value) {
        String normalized = normalizeForSearch(value);
        if (normalized.isEmpty()) return "";
        int first = normalized.codePointAt(0);
        return new String(Character.toChars(first));
    }

    private static int fold(int codePoint) {
        switch (codePoint) {
            case 0x0622: // آ
            case 0x0623: // أ
            case 0x0625: // إ
            case 0x0671: // ٱ
                return 0x0627; // ا
            case 0x0624: // ؤ
                return 0x0648; // و
            case 0x0626: // ئ
            case 0x0649: // ى
                return 0x064A; // ي
            case 0x0629: // ة
                return 0x0647; // ه
            default:
                if (codePoint >= 0x0660 && codePoint <= 0x0669)
                    return '0' + (codePoint - 0x0660);
                if (codePoint >= 0x06F0 && codePoint <= 0x06F9)
                    return '0' + (codePoint - 0x06F0);
                return codePoint;
        }
    }

    private static boolean isCombiningMark(int type) {
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isArabicMark(int codePoint) {
        return (codePoint >= 0x0610 && codePoint <= 0x061A)
                || (codePoint >= 0x064B && codePoint <= 0x065F)
                || codePoint == 0x0670
                || (codePoint >= 0x06D6 && codePoint <= 0x06ED);
    }
}
