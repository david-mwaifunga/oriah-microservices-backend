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

    private Screens() {
    }
}
