package com.oriah.notification.text;

/**
 * The names of the three admin screens an alert sends staff to, in one place. An alert email is only useful if the words it uses are the words
 * on the screen, so the admin screens must carry exactly these (docs/FRONTEND_WORK.md says so), or these change with the screens.
 */
public final class Screens {

    /** Orders whose customer says they have paid: it shows the amount to look for and the reference given. */
    public static final String AWAITING_PAYMENT = "the Awaiting payment list";
    /** Recorded money that has not simply paid its sale. */
    public static final String SETTLEMENTS = "the Settlements list";
    /** Where every priced piece stands against what it cost. */
    public static final String BELOW_COST = "Prices, Below cost";

    /** Where the owner pays a refund that is owed (Phase 12). */
    public static final String REFUNDS_OWED = "Payments, Refunds owed";
    /** Where staff see each payment through ZynlePay and may ask ZynlePay about one (Phase 13). */
    public static final String PROVIDER_PAYMENTS = "Payments, ZynlePay payments";

    private Screens() {
    }
}
