package io.github.kamilkrzywanski.barleader.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The little JSON the BarBeR labels need: objects as maps in file order, arrays as lists, numbers
 * as doubles, and strings, booleans and null as themselves.
 */
final class Json {
    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value();
        json.space();
        if (json.at != text.length()) {
            throw json.error("trailing text");
        }
        return value;
    }

    private Object value() {
        space();
        if (at >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return word("true", Boolean.TRUE);
            case 'f':
                return word("false", Boolean.FALSE);
            case 'n':
                return word("null", null);
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> object = new LinkedHashMap<>();
        at++;
        space();
        if (peek() == '}') {
            at++;
            return object;
        }
        while (true) {
            space();
            String key = string();
            space();
            expect(':');
            object.put(key, value());
            space();
            if (peek() == ',') {
                at++;
            } else {
                expect('}');
                return object;
            }
        }
    }

    private List<Object> array() {
        List<Object> array = new ArrayList<>();
        at++;
        space();
        if (peek() == ']') {
            at++;
            return array;
        }
        while (true) {
            array.add(value());
            space();
            if (peek() == ',') {
                at++;
            } else {
                expect(']');
                return array;
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = text.charAt(at++);
            switch (escape) {
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> out.append(escape);
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw error("unexpected character");
        }
        return Double.valueOf(text.substring(start, at));
    }

    private Object word(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error("unexpected word");
        }
        at += word.length();
        return value;
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected " + c);
        }
        at++;
    }

    private char peek() {
        return at < text.length() ? text.charAt(at) : '\0';
    }

    private void space() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at " + at);
    }
}
