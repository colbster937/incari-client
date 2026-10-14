package incari.classlib.util;

import java.util.regex.PatternSyntaxException;

/**
 * Translates {@code FileSystem.getPathMatcher} glob patterns to regular expressions, per the
 * documented glob syntax: {@code *} within a name, {@code **} across names, {@code ?} one
 * character, {@code [...]} (with {@code !} negation and ranges) within a name, {@code {a,b}}
 * alternatives (not nested), and {@code \} escaping the next character.
 */
public final class Globs {

    private Globs() {}

    public static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        boolean inGroup = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '\\' -> {
                    if (++i >= glob.length()) {
                        throw new PatternSyntaxException("No character to escape", glob, i - 1);
                    }
                    quote(regex, glob.charAt(i));
                }
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '[' -> i = bracket(glob, i, regex);
                case '{' -> {
                    if (inGroup) {
                        throw new PatternSyntaxException("Cannot nest groups", glob, i);
                    }
                    regex.append("(?:(?:");
                    inGroup = true;
                }
                case '}' -> {
                    if (inGroup) {
                        regex.append("))");
                        inGroup = false;
                    } else {
                        quote(regex, c);
                    }
                }
                case ',' -> {
                    if (inGroup) {
                        regex.append(")|(?:");
                    } else {
                        quote(regex, c);
                    }
                }
                default -> quote(regex, c);
            }
        }
        if (inGroup) {
            throw new PatternSyntaxException("Missing '}'", glob, glob.length() - 1);
        }
        return regex.append('$').toString();
    }

    private static int bracket(String glob, int start, StringBuilder regex) {
        regex.append("[[^/]&&[");
        int i = start + 1;
        if (i < glob.length() && glob.charAt(i) == '!') {
            regex.append('^');
            i++;
        } else if (i < glob.length() && glob.charAt(i) == '^') {
            regex.append("\\^");
            i++;
        }
        for (; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == ']') {
                regex.append("]]");
                return i;
            }
            if (c == '/') {
                throw new PatternSyntaxException("Explicit 'name separator' in class", glob, i);
            }
            if (c == '-') {
                regex.append('-');
            } else {
                quote(regex, c);
            }
        }
        throw new PatternSyntaxException("Missing ']'", glob, glob.length() - 1);
    }

    private static void quote(StringBuilder regex, char c) {
        if ("\\^$.|?*+()[]{}&-".indexOf(c) >= 0) {
            regex.append('\\');
        }
        regex.append(c);
    }
}
