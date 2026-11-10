package com.oriah.procurement.model;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.oriah.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Someone Oriah buys from. Recorded once, with what CLIENT_DECISIONS 4.1 names:
 * "name, country, who you deal with there, how you pay them".
 *
 * <h2>What a supplier is not</h2>
 *
 * <p>"How you pay them" is a <em>description</em> ({@link #paymentNotes()}), not a
 * payment record. There is no supplier-payments ledger: the client ruled out
 * deposits and part-payments (Q1), and what was paid is each consignment line's
 * all-in bank total (Phase 4).
 *
 * <p>{@link #country()} is the supplier's own country, where they are based ("who
 * you deal with <em>there</em>"). The country the goods come from belongs to a
 * consignment line, not to the supplier.
 *
 * <h2>Two switches, two meanings</h2>
 *
 * <ul>
 *   <li><strong>inactive</strong> ({@link #deactivate()}): a real supplier we have
 *       stopped using. Phase 4 will give it no new purchase orders.</li>
 *   <li><strong>deleted</strong> ({@link #markDeleted()}): correcting a record that
 *       should never have existed. Its name becomes free again.</li>
 * </ul>
 *
 * <h2>Rules</h2>
 *
 * <p>Every rule is enforced here, in the constructor and in {@link #change}, so a
 * supplier that breaks one can never exist. A refused {@code change} leaves the
 * supplier exactly as it was: everything is checked before anything is assigned.
 * Whether a name is <em>already taken</em> is not a rule of one supplier; the
 * service checks it and the database's partial unique index backs it up.
 */
@Entity
@Table(name = "suppliers")
public class Supplier extends BaseEntity {

    static final int NAME_MAX = 120;
    static final int CONTACT_NAME_MAX = 120;
    static final int CONTACT_DETAILS_MAX = 500;
    static final int PAYMENT_NOTES_MAX = 500;

    /** The countries of ISO 3166-1 alpha-2: "ZM" yes; "UK" (it is GB), "XX" and "ZZ" no. */
    private static final Set<String> COUNTRIES = Set.copyOf(
            Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2));

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(nullable = false, length = NAME_MAX)
    private String name;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(name = "contact_name", length = CONTACT_NAME_MAX)
    private String contactName;

    @Column(name = "contact_details", length = CONTACT_DETAILS_MAX)
    private String contactDetails;

    @Column(name = "payment_notes", length = PAYMENT_NOTES_MAX)
    private String paymentNotes;

    @Column(nullable = false)
    private boolean active;

    /**
     * The optimistic lock: two requests that both read this supplier and both
     * try to change it cannot both win, so a lost update is a 409 instead of a
     * silent overwrite. A wrapper type, null until saved: that is how Spring Data
     * sees a new supplier (whose id we assign ourselves) as new and inserts it
     * without a lookup first.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    protected Supplier() {}

    public Supplier(String name, String country, String contactName, String contactDetails, String paymentNotes) {
        Checked c = Checked.of(name, country, contactName, contactDetails, paymentNotes);
        this.id = UUID.randomUUID();
        this.active = true;
        apply(c);
    }

    /**
     * Replaces the five descriptive fields, under the same rules as creation.
     * Nothing changes unless all five are acceptable.
     */
    public void change(String name, String country, String contactName, String contactDetails, String paymentNotes) {
        apply(Checked.of(name, country, contactName, contactDetails, paymentNotes));
    }

    /** Stop using this supplier. Doing it twice changes nothing. */
    public void deactivate() {
        this.active = false;
    }

    /** Start using this supplier again. Doing it twice changes nothing. */
    public void activate() {
        this.active = true;
    }

    /**
     * Is this an ISO 3166-1 alpha-2 country code ("zm" and " ZM " count; "UK",
     * "XX" and "Zambia" do not)? The one list, used by the aggregate itself and
     * by request validation.
     */
    public static boolean isCountryCode(String value) {
        return value != null && COUNTRIES.contains(value.strip().toUpperCase(Locale.ROOT));
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String country() {
        return country;
    }

    public String contactName() {
        return contactName;
    }

    public String contactDetails() {
        return contactDetails;
    }

    public String paymentNotes() {
        return paymentNotes;
    }

    public boolean isActive() {
        return active;
    }

    public Long version() {
        return version;
    }

    private void apply(Checked c) {
        this.name = c.name;
        this.country = c.country;
        this.contactName = c.contactName;
        this.contactDetails = c.contactDetails;
        this.paymentNotes = c.paymentNotes;
    }

    /** The five fields, tidied and checked, before any of them is assigned. */
    private record Checked(String name, String country, String contactName, String contactDetails,
                           String paymentNotes) {

        static Checked of(String name, String country, String contactName, String contactDetails,
                          String paymentNotes) {
            return new Checked(
                    required("name", name, NAME_MAX),
                    isoCountry(country),
                    optional("contactName", contactName, CONTACT_NAME_MAX),
                    optional("contactDetails", contactDetails, CONTACT_DETAILS_MAX),
                    optional("paymentNotes", paymentNotes, PAYMENT_NOTES_MAX));
        }

        private static String required(String field, String value, int max) {
            String tidy = value == null ? "" : value.strip();
            if (tidy.isEmpty()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return atMost(field, tidy, max);
        }

        private static String optional(String field, String value, int max) {
            String tidy = value == null ? "" : value.strip();
            return tidy.isEmpty() ? null : atMost(field, tidy, max);
        }

        private static String atMost(String field, String tidy, int max) {
            if (tidy.length() > max) {
                throw new IllegalArgumentException(field + " must be at most " + max + " characters");
            }
            return tidy;
        }

        private static String isoCountry(String value) {
            String tidy = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
            if (tidy.isEmpty()) {
                throw new IllegalArgumentException("country is required");
            }
            if (!COUNTRIES.contains(tidy)) {
                throw new IllegalArgumentException(
                        "country must be an ISO 3166-1 alpha-2 code such as ZM, CN or AE");
            }
            return tidy;
        }
    }
}
