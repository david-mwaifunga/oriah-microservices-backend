package com.oriah.quote;

/**
 * Who a parcel is for and how to reach them (Phase 8, design D8): the rule, shared by checkout (which asks for them) and order (which keeps them),
 * for what a good recipient name and phone number look like and the one spelling they are kept in.
 *
 * <p>Pure JDK, like the rest of this module: depending on it cannot change either service's wiring. The services turn the plain
 * {@link IllegalArgumentException} messages into a field error at their own edge.
 */
public final class DeliveryContact {

    public static final int NAME_MIN = 2;
    public static final int NAME_MAX = 120;

    /** Zambia's country code and the nine digits that follow it. */
    private static final String ZAMBIA = "260";
    private static final int ZAMBIA_LENGTH = 12;

    private DeliveryContact() {
    }

    /**
     * @return the name as kept: trimmed, with every run of spaces, tabs and line breaks made one space
     * @throws IllegalArgumentException when it is missing, shorter than 2 or longer than 120 characters, has no letter in it, or has a control or
     *                                  direction-changing character (which could scramble a packing slip)
     */
    public static String name(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("the recipient's name is required");
        }
        String name = raw.replaceAll("[\\p{Z}\\s]+", " ").strip();
        if (name.length() < NAME_MIN) {
            throw new IllegalArgumentException("the recipient's name needs at least " + NAME_MIN + " characters");
        }
        if (name.length() > NAME_MAX) {
            throw new IllegalArgumentException("the recipient's name is at most " + NAME_MAX + " characters");
        }
        if (name.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL || Character.getType(c) == Character.FORMAT)) {
            throw new IllegalArgumentException("the recipient's name has a character that cannot be printed");
        }
        if (name.codePoints().noneMatch(Character::isLetter)) {
            throw new IllegalArgumentException("the recipient's name must have letters in it");
        }
        return name;
    }

    /**
     * Reads a phone number the way a Zambian types it and keeps it as {@code +<country code><number>}: spaces, dots, hyphens and brackets are
     * dropped; {@code 00} means {@code +}; a leading {@code 0} means Zambia; {@code 260} without the plus is Zambia too; the trunk {@code 0} some
     * people type after {@code +260} is dropped.
     *
     * @throws IllegalArgumentException when it is missing, or cannot be read with confidence: not digits, no country code, a Zambian number that is
     *                                  not nine digits, fewer than 8 or more than 15 digits
     */
    public static String phone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("a phone number is required");
        }
        String typed = raw.replaceAll("[\\p{Z}\\s.\\-()\\[\\]]", "");
        boolean plus = typed.startsWith("+");
        String digits = plus ? typed.substring(1) : typed;
        if (!digits.matches("[0-9]+")) {
            throw new IllegalArgumentException("the phone number may have only digits (0 to 9), with a + at the start for a country code");
        }
        String international;
        if (plus) {
            international = digits;
        } else if (digits.startsWith("00")) {
            international = digits.substring(2);
        } else if (digits.startsWith(ZAMBIA)) {
            international = digits;
        } else if (digits.startsWith("0")) {
            international = ZAMBIA + digits.substring(1);
        } else {
            throw new IllegalArgumentException("the phone number must start with + and the country code, or with 0 for a Zambian number");
        }
        if (international.startsWith(ZAMBIA + "0")) {
            // "+260 (0) 97 123 4567": the 0 a Zambian dials at home is not part of the number from abroad.
            international = ZAMBIA + international.substring(ZAMBIA.length() + 1);
        }
        if (international.startsWith(ZAMBIA) && international.length() != ZAMBIA_LENGTH) {
            throw new IllegalArgumentException("a Zambian phone number is +260 and nine digits");
        }
        if (!international.matches("[1-9][0-9]{7,14}")) {
            throw new IllegalArgumentException("the phone number must have 8 to 15 digits after its country code, the first not 0");
        }
        return "+" + international;
    }
}
