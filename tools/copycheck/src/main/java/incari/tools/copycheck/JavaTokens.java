package incari.tools.copycheck;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately small Java lexer: enough to compare code by its tokens while ignoring
 * whitespace, comments, formatting, and the {@code package}/{@code import} header.
 *
 * <p>It doesn't need to be a correct Java lexer, only a consistent one: the same text must
 * always give the same tokens, on both sides of the comparison.
 */
final class JavaTokens {

    /** One token and the 1-based line it starts on. */
    record Token(String text, int line) {}

    private JavaTokens() {}

    static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int line = 1;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                line += count(source, i, stop, '\n');
                i = stop;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? n : end + 3;
                tokens.add(new Token(source.substring(i, stop), line));
                line += count(source, i, stop, '\n');
                i = stop;
            } else if (c == '"' || c == '\'') {
                int stop = i + 1;
                while (stop < n && source.charAt(stop) != c && source.charAt(stop) != '\n') {
                    stop += source.charAt(stop) == '\\' ? 2 : 1;
                }
                stop = Math.min(stop + 1, n);
                tokens.add(new Token(source.substring(i, stop), line));
                i = stop;
            } else if (Character.isJavaIdentifierStart(c) || Character.isDigit(c)) {
                int stop = i + 1;
                while (stop < n
                        && (Character.isJavaIdentifierPart(source.charAt(stop))
                                || isDecimalPoint(source, stop, Character.isDigit(c)))) {
                    stop++;
                }
                tokens.add(new Token(source.substring(i, stop), line));
                i = stop;
            } else {
                tokens.add(new Token(String.valueOf(c), line));
                i++;
            }
        }
        return dropHeader(tokens);
    }

    /** Drops {@code package} and {@code import} statements: every file shares their shape. */
    private static List<Token> dropHeader(List<Token> tokens) {
        List<Token> body = new ArrayList<>(tokens.size());
        boolean skipping = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            boolean statementStart = i == 0 || tokens.get(i - 1).text().equals(";");
            if (!skipping
                    && statementStart
                    && (token.text().equals("package") || token.text().equals("import"))) {
                skipping = true;
            }
            if (!skipping) {
                body.add(token);
            } else if (token.text().equals(";")) {
                skipping = false;
            }
        }
        return body;
    }

    /** A {@code .} inside a number literal ({@code 1.5}), as opposed to a member access. */
    private static boolean isDecimalPoint(String source, int at, boolean inNumber) {
        return inNumber
                && source.charAt(at) == '.'
                && at + 1 < source.length()
                && Character.isDigit(source.charAt(at + 1));
    }

    private static int count(String text, int from, int to, char c) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }
}
