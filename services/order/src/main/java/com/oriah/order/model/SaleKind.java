package com.oriah.order.model;

/**
 * The kind of staff sale, chosen by the person on the form: {@code LIVE_PHONE} is happening now (dated now), {@code LATE_PAPER} was written on paper and is
 * typed in later (dated at its typed true date, priced as of that date, always with a reason).
 */
public enum SaleKind {
    LIVE_PHONE, LATE_PAPER
}
