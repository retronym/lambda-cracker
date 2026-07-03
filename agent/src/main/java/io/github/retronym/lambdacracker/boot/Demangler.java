package io.github.retronym.lambdacracker.boot;

/**
 * Maps implementation-method names back to the enclosing method the lambda was written in.
 * Per-compiler encodings; extend the table as real output from each compiler is inspected.
 */
final class Demangler {
    private Demangler() {}

    /**
     * @return the enclosing method name, {@code ""} when the impl is a lambda body whose
     * name carries no enclosing-method info, or {@code null} when the impl is a plain
     * method (i.e. a method reference).
     */
    static String enclosingMethod(String implName) {
        String n = implName;
        if (n.endsWith("$adapted")) n = n.substring(0, n.length() - "$adapted".length());

        if (n.startsWith("lambda$"))          // javac: lambda$<method>$<n>, or lambda$<method>$<hash>$<n>
            return stripTrailingHash(stripTrailingIndex(n.substring("lambda$".length())));
        if (n.startsWith("$anonfun$"))        // scalac 2.12+: $anonfun$<method>$<n>, scalac 3: $anonfun$<n>
            return stripTrailingIndex(n.substring("$anonfun$".length()));
        int i = n.indexOf("$$anonfun$");      // alternative nesting encodings
        if (i > 0) return n.substring(0, i);
        return null;
    }

    /**
     * javac names serializable-lambda impl methods {@code lambda$<method>$<8-hex-char-hash>$<n>}
     * (a hardening measure against deserialization gadget forgery) instead of the plain
     * {@code lambda$<method>$<n>} used for non-serializable lambdas. Strip that hash segment,
     * if present, after {@link #stripTrailingIndex} has already removed the trailing index.
     */
    private static String stripTrailingHash(String s) {
        int n = s.length();
        if (n < 9 || s.charAt(n - 9) != '$') return s;
        for (int i = n - 8; i < n; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return s;
        }
        return s.substring(0, n - 9);
    }

    private static String stripTrailingIndex(String s) {
        int i = s.length();
        while (i > 0 && Character.isDigit(s.charAt(i - 1))) i--;
        if (i == s.length()) return s;                       // no trailing digits: plain name
        if (i == 0) return "";                               // digits only: no enclosing info
        if (s.charAt(i - 1) == '$') return s.substring(0, i - 1);
        return s;                                            // digits are part of the name
    }
}
