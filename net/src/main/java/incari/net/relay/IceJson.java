package incari.net.relay;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * ICE candidates in the JSON Eagler's peers exchange through a relay: an array of objects with
 * {@code candidate} and {@code sdpMLineIndex}, the index written as a string. Small enough to
 * write and read by hand; the net module has no JSON library.
 */
final class IceJson {

    /** One candidate. */
    record Candidate(String candidate, int sdpMLineIndex) {}

    private IceJson() {}

    static String write(List<Candidate> candidates) {
        StringBuilder json = new StringBuilder("[");
        for (Candidate c : candidates) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"sdpMLineIndex\":\"").append(c.sdpMLineIndex()).append("\",\"candidate\":");
            quote(json, c.candidate());
            json.append('}');
        }
        return json.append(']').toString();
    }

    /** The candidates in {@code json}: an array of them, or a single one. */
    static List<Candidate> read(String json) {
        List<Candidate> out = new ArrayList<>();
        int at = 0;
        while (true) {
            int open = json.indexOf('{', at);
            if (open < 0) {
                return out;
            }
            int close = closing(json, open);
            String object = json.substring(open, close + 1);
            String candidate = field(object, "candidate");
            String index = field(object, "sdpMLineIndex");
            if (candidate != null) {
                int line = 0;
                if (index != null) {
                    try {
                        line = Integer.parseInt(index.trim());
                    } catch (NumberFormatException e) {
                        // Left at 0: a single-stream connection has only line 0 anyway.
                    }
                }
                out.add(new Candidate(candidate, line));
            }
            at = close + 1;
        }
    }

    /** The end of the object at {@code open}, skipping braces inside strings. */
    private static int closing(String json, int open) {
        boolean inString = false;
        for (int i = open; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '}') {
                return i;
            }
        }
        return json.length() - 1;
    }

    /** A field's value, string or number, unescaped; null if absent. */
    private static @Nullable String field(String object, String name) {
        int key = object.indexOf('"' + name + '"');
        if (key < 0) {
            return null;
        }
        int colon = object.indexOf(':', key + name.length() + 2);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < object.length() && Character.isWhitespace(object.charAt(i))) {
            i++;
        }
        if (i >= object.length()) {
            return null;
        }
        if (object.charAt(i) != '"') {
            int end = i;
            while (end < object.length() && ",}".indexOf(object.charAt(end)) < 0) {
                end++;
            }
            return object.substring(i, end).trim();
        }
        StringBuilder value = new StringBuilder();
        for (int j = i + 1; j < object.length(); j++) {
            char c = object.charAt(j);
            if (c == '"') {
                return value.toString();
            }
            if (c == '\\' && j + 1 < object.length()) {
                char next = object.charAt(++j);
                switch (next) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        int code = hex4(object, j + 1);
                        if (code >= 0) {
                            value.append((char) code);
                            j += 4;
                        }
                        // A broken escape is left out: the text comes from a stranger's peer, and
                        // a candidate it spoils just won't connect.
                    }
                    default -> value.append(next);
                }
            } else {
                value.append(c);
            }
        }
        return value.toString();
    }

    /** The four hex digits at {@code at} as a number, or -1 if there aren't four there. */
    private static int hex4(String text, int at) {
        if (at + 4 > text.length()) {
            return -1;
        }
        int code = 0;
        for (int i = at; i < at + 4; i++) {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            code = code * 16 + digit;
        }
        return code;
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        out.append('"');
    }
}
