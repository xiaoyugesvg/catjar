package android.text;

/** TextUtils 的功能实现（很多 spider 拿它判空/拼接） */
public final class TextUtils {
    private TextUtils() {
    }

    public static boolean isEmpty(CharSequence s) {
        return s == null || s.length() == 0;
    }

    public static boolean equals(CharSequence a, CharSequence b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.toString().equals(b.toString());
    }

    public static String join(CharSequence delimiter, Object[] tokens) {
        StringBuilder b = new StringBuilder();
        if (tokens == null) return "";
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) b.append(delimiter);
            b.append(tokens[i]);
        }
        return b.toString();
    }

    public static String join(CharSequence delimiter, Iterable<?> tokens) {
        StringBuilder b = new StringBuilder();
        boolean first = true;
        if (tokens == null) return "";
        for (Object t : tokens) {
            if (!first) b.append(delimiter);
            b.append(t);
            first = false;
        }
        return b.toString();
    }

    public static String[] split(String text, String expression) {
        if (text == null || text.length() == 0) return new String[0];
        return text.split(expression);
    }
}
