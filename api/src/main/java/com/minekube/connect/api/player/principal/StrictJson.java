package com.minekube.connect.api.player.principal;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal closed-object JSON parser for the frozen principal envelope. */
final class StrictJson {
    private final String input;
    private int position;
    private int stringBytes;

    private StrictJson(String input) {
        this.input = input;
    }

    static Map<String, Object> parseObject(String input, int decodedBytes) {
        StrictJson parser = new StrictJson(input);
        Map<String, Object> object = parser.object(1);
        parser.space();
        if (parser.position != input.length()
                || parser.stringBytes > 24_576
                || decodedBytes + parser.stringBytes > 65_536) {
            throw malformed();
        }
        return object;
    }

    private Map<String, Object> object(int depth) {
        if (depth > 4) throw malformed();
        expect('{');
        space();
        Map<String, Object> result = new LinkedHashMap<>();
        if (take('}')) return result;
        while (true) {
            if (result.size() == 32) throw malformed();
            String name = string();
            if (result.containsKey(name)) throw malformed();
            space();
            expect(':');
            space();
            result.put(name, value(depth));
            space();
            if (take('}')) return result;
            expect(',');
            space();
        }
    }

    private Object value(int depth) {
        if (position >= input.length()) throw malformed();
        char value = input.charAt(position);
        if (value == '"') return string();
        if (value == '{') return object(depth + 1);
        if (value == 't') return literal("true", Boolean.TRUE);
        if (value == 'f') return literal("false", Boolean.FALSE);
        if (value == 'n') return literal("null", null);
        if (value == '[') throw malformed();
        return number();
    }

    private Object literal(String literal, Object value) {
        if (!input.regionMatches(position, literal, 0, literal.length())) throw malformed();
        position += literal.length();
        return value;
    }

    private Long number() {
        int start = position;
        if (take('-') && position == input.length()) throw malformed();
        if (take('0')) {
            if (position < input.length() && Character.isDigit(input.charAt(position))) throw malformed();
        } else {
            int digits = position;
            while (position < input.length() && input.charAt(position) >= '0'
                    && input.charAt(position) <= '9') position++;
            if (position == digits) throw malformed();
        }
        if (position < input.length()) {
            char next = input.charAt(position);
            if (next == '.' || next == 'e' || next == 'E' || next == '+') throw malformed();
        }
        try {
            return Long.valueOf(input.substring(start, position));
        } catch (NumberFormatException ignored) {
            throw malformed();
        }
    }

    private String string() {
        expect('"');
        StringBuilder result = new StringBuilder();
        while (position < input.length()) {
            char value = input.charAt(position++);
            if (value == '"') {
                String decoded = result.toString();
                if (decoded.indexOf('\0') >= 0) throw malformed();
                stringBytes += decoded.getBytes(StandardCharsets.UTF_8).length;
                return decoded;
            }
            if (value < 0x20) throw malformed();
            if (value == '\\') {
                if (position == input.length()) throw malformed();
                char escape = input.charAt(position++);
                switch (escape) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u': appendUnicode(result); break;
                    default: throw malformed();
                }
                continue;
            }
            if (Character.isHighSurrogate(value)) {
                if (position == input.length() || !Character.isLowSurrogate(input.charAt(position))) {
                    throw malformed();
                }
                result.append(value).append(input.charAt(position++));
            } else if (Character.isLowSurrogate(value)) {
                throw malformed();
            } else {
                result.append(value);
            }
        }
        throw malformed();
    }

    private void appendUnicode(StringBuilder result) {
        char high = hex16();
        if (Character.isHighSurrogate(high)) {
            if (position + 2 > input.length()
                    || input.charAt(position) != '\\'
                    || input.charAt(position + 1) != 'u') throw malformed();
            position += 2;
            char low = hex16();
            if (!Character.isLowSurrogate(low)) throw malformed();
            result.append(high).append(low);
        } else if (Character.isLowSurrogate(high)) {
            throw malformed();
        } else {
            result.append(high);
        }
    }

    private char hex16() {
        if (position + 4 > input.length()) throw malformed();
        int result = 0;
        for (int index = 0; index < 4; index++) {
            int digit = Character.digit(input.charAt(position++), 16);
            if (digit < 0) throw malformed();
            result = (result << 4) | digit;
        }
        return (char) result;
    }

    private void expect(char value) {
        if (!take(value)) throw malformed();
    }

    private boolean take(char value) {
        if (position < input.length() && input.charAt(position) == value) {
            position++;
            return true;
        }
        return false;
    }

    private void space() {
        while (position < input.length()) {
            char value = input.charAt(position);
            if (value != ' ' && value != '\n' && value != '\r' && value != '\t') return;
            position++;
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException(PrincipalError.MALFORMED.name());
    }
}
