package com.provider.nps.message;

/** Shared validation for caller-supplied NPS fields. */
public final class NpsFields {
    private NpsFields() {
    }
    public static String required(String value, String name, int max) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing required metadata: " + name);
        if (value.length() > max)
            throw new IllegalArgumentException(name + " exceeds " + max + " characters");
        return value;
    }
    public static String member(String value, String name) {
        required(value, name, 6);
        if (!value.matches("[0-9]{6}"))
            throw new IllegalArgumentException(name + " must contain six digits");
        return value;
    }
    public static String xml(String value) {
        if (value == null)
            return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
    public static String agent(String member) {
        return "<FinInstnId><ClrSysMmbId><MmbId>" + xml(member) + "</MmbId></ClrSysMmbId></FinInstnId>";
    }
}
