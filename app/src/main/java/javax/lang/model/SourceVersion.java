package javax.lang.model;

/**
 * Stub for javax.lang.model.SourceVersion, which is part of the Java compiler API
 * and absent from Android's runtime. GraphHopper 7.0 uses it to validate encoded
 * value names (e.g. "bike_access"). Providing this stub allows GraphHopper to
 * initialize normally on Android without crashing with NoClassDefFoundError.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4, RELEASE_5, RELEASE_6,
    RELEASE_7, RELEASE_8, RELEASE_9, RELEASE_10, RELEASE_11, RELEASE_12, RELEASE_13,
    RELEASE_14, RELEASE_15, RELEASE_16, RELEASE_17, RELEASE_18, RELEASE_19, RELEASE_20,
    RELEASE_21;

    public static SourceVersion latest() { return RELEASE_21; }
    public static SourceVersion latestSupported() { return RELEASE_17; }

    public static boolean isIdentifier(CharSequence name) {
        if (name == null || name.length() == 0) return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }

    public static boolean isName(CharSequence name) {
        if (name == null || name.length() == 0) return false;
        for (String part : name.toString().split("\\.", -1)) {
            if (part.isEmpty() || !isIdentifier(part)) return false;
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) {
        // GraphHopper encoded value names like "bike_access" are never Java keywords.
        // Return false so none are rejected by GraphHopper's validator.
        return false;
    }
}
