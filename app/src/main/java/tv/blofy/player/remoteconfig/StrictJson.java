package tv.blofy.player.remoteconfig;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON parser for signed configuration. Duplicate keys are rejected. */
final class StrictJson {
    private static final int MAX_DEPTH = 12;
    private static final int MAX_NODES = 4096;

    private StrictJson() {}

    static Object parse(byte[] utf8, int maximumBytes) throws ParseException {
        if (utf8 == null || utf8.length == 0 || utf8.length > maximumBytes) {
            throw new ParseException("JSON size is invalid");
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new ParseException("JSON is not valid UTF-8");
        }
        Parser parser = new Parser(text);
        Object value = parser.value(0);
        parser.space();
        if (!parser.end()) throw new ParseException("Trailing JSON data");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) throws ParseException {
        if (!(value instanceof Map)) throw new ParseException("JSON object required");
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int index;
        private int nodes;

        Parser(String text) { this.text = text; }

        boolean end() { return index >= text.length(); }

        void space() {
            while (!end()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') index++;
                else break;
            }
        }

        Object value(int depth) throws ParseException {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw new ParseException("JSON complexity limit exceeded");
            }
            space();
            if (end()) throw new ParseException("Unexpected end of JSON");
            char c = text.charAt(index);
            if (c == '{') return objectValue(depth + 1);
            if (c == '[') return arrayValue(depth + 1);
            if (c == '"') return string();
            if (c == 't') { literal("true"); return Boolean.TRUE; }
            if (c == 'f') { literal("false"); return Boolean.FALSE; }
            if (c == 'n') { literal("null"); return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw new ParseException("Unexpected JSON token");
        }

        private Map<String, Object> objectValue(int depth) throws ParseException {
            index++;
            space();
            Map<String, Object> result = new LinkedHashMap<>();
            if (take('}')) return result;
            while (true) {
                space();
                if (end() || text.charAt(index) != '"') {
                    throw new ParseException("Object key must be a string");
                }
                String key = string();
                if (result.containsKey(key)) throw new ParseException("Duplicate JSON key");
                space();
                require(':');
                result.put(key, value(depth));
                space();
                if (take('}')) return result;
                require(',');
            }
        }

        private List<Object> arrayValue(int depth) throws ParseException {
            index++;
            space();
            List<Object> result = new ArrayList<>();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth));
                space();
                if (take(']')) return result;
                require(',');
            }
        }

        private String string() throws ParseException {
            require('"');
            StringBuilder result = new StringBuilder();
            while (!end()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    validateSurrogates(result);
                    return result.toString();
                }
                if (c < 0x20) throw new ParseException("Control character in JSON string");
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (end()) throw new ParseException("Invalid JSON escape");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u': result.append(unicode()); break;
                    default: throw new ParseException("Invalid JSON escape");
                }
            }
            throw new ParseException("Unterminated JSON string");
        }

        private char unicode() throws ParseException {
            if (index + 4 > text.length()) throw new ParseException("Invalid unicode escape");
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) throw new ParseException("Invalid unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Number number() throws ParseException {
            int start = index;
            if (take('-') && end()) throw new ParseException("Invalid JSON number");
            if (take('0')) {
                if (!end() && Character.isDigit(text.charAt(index))) {
                    throw new ParseException("Leading zero in JSON number");
                }
            } else {
                digits();
            }
            boolean decimal = false;
            if (take('.')) {
                decimal = true;
                digits();
            }
            if (!end() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!end() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                digits();
            }
            String raw = text.substring(start, index);
            try {
                if (!decimal) return Long.parseLong(raw);
                double value = Double.parseDouble(raw);
                if (!Double.isFinite(value)) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException invalid) {
                throw new ParseException("Invalid JSON number");
            }
        }

        private void digits() throws ParseException {
            int start = index;
            while (!end() && Character.isDigit(text.charAt(index))) index++;
            if (start == index) throw new ParseException("JSON digit required");
        }

        private void literal(String expected) throws ParseException {
            if (!text.regionMatches(index, expected, 0, expected.length())) {
                throw new ParseException("Invalid JSON literal");
            }
            index += expected.length();
        }

        private boolean take(char expected) {
            if (!end() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) throws ParseException {
            if (!take(expected)) throw new ParseException("Expected JSON delimiter");
        }

        private static void validateSurrogates(CharSequence value) throws ParseException {
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (Character.isHighSurrogate(current)) {
                    if (i + 1 >= value.length()
                            || !Character.isLowSurrogate(value.charAt(++i))) {
                        throw new ParseException("Unpaired JSON surrogate");
                    }
                } else if (Character.isLowSurrogate(current)) {
                    throw new ParseException("Unpaired JSON surrogate");
                }
            }
        }
    }

    static final class ParseException extends Exception {
        ParseException(String message) { super(message); }
    }
}
