package android.util;

/** Base64 的功能实现：spider 常用 NO_WRAP / URL_SAFE */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;

    private Base64() {
    }

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static byte[] encode(byte[] input, int flags) {
        // 注意：类名就叫 Base64，必须全限定 java.util.Base64，否则会解析成自己
        java.util.Base64.Encoder enc = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlEncoder()
                : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) enc = enc.withoutPadding();
        byte[] out = enc.encode(input == null ? new byte[0] : input);
        if ((flags & NO_WRAP) == 0) {
            return out;
        }
        // NO_WRAP：去掉换行（Java 的编码器本来就不换行，这里做兼容处理）
        return new String(out, java.nio.charset.StandardCharsets.ISO_8859_1)
                .replace("\r", "").replace("\n", "")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    public static byte[] decode(String input, int flags) {
        if (input == null) return new byte[0];
        String s = input.replace("\r", "").replace("\n", "").trim();
        java.util.Base64.Decoder dec = (flags & URL_SAFE) != 0
                ? java.util.Base64.getUrlDecoder()
                : java.util.Base64.getDecoder();
        return dec.decode(s);
    }

    public static byte[] decode(byte[] input, int flags) {
        return decode(new String(input == null ? new byte[0] : input,
                java.nio.charset.StandardCharsets.ISO_8859_1), flags);
    }
}
