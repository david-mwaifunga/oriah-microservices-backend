package com.oriah.procurement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Edit a supplier, sending only what changes.
 *
 * <h2>Three states per field</h2>
 *
 * <p>A JSON body can say three different things about a field, and each means
 * something different, so a plain {@code null} in a Java field cannot hold them
 * all:
 *
 * <ul>
 *   <li><b>left out</b>: leave it as it is;</li>
 *   <li><b>sent as {@code null} or blank</b>: clear it (the three optional fields);</li>
 *   <li><b>sent with a value</b>: replace it.</li>
 * </ul>
 *
 * <p>Without the first two being different, an edit of one field would either
 * wipe the others or make clearing a contact impossible. Jackson calls a setter
 * only for a property that is in the body, and calls it with {@code null} when the
 * body says {@code null}, so each setter here notes that its field was sent. (An
 * {@code Optional} field does not work for this: Jackson 3 turns an explicit
 * {@code null} into an empty {@code Optional}, which is the same as left out.)
 *
 * <p>{@code name}, {@code country} and {@code active} cannot be cleared. Sending
 * them empty is refused as a field error by {@code SupplierService.update}, which
 * checks every field that was sent against the same rules as adding a supplier.
 *
 * <p>{@code version} is the version of the supplier the edit was made from, the
 * {@code version} of the response the form was loaded with. If someone else has
 * saved since, it is no longer current and the edit is refused (409), so a form
 * that was open for an hour cannot silently overwrite a colleague's change, for
 * instance to how Oriah pays a supplier. Leaving it out skips the check, and the
 * last save wins.
 *
 * <p>This class is only ever read from a request, never written to one. The
 * getters exist so the generated API description lists the fields.
 */
@Schema(name = "UpdateSupplierRequest",
        description = "Any of these fields. A field left out is unchanged. contactName, contactDetails and "
                + "paymentNotes are cleared by sending null or blank text. name, country and active cannot be "
                + "empty. Send version, from the supplier you loaded, so that an edit made from an out-of-date "
                + "copy is refused instead of overwriting a colleague's change.")
public class UpdateSupplierRequest {

    private boolean nameSent;
    private String name;
    private boolean countrySent;
    private String country;
    private boolean contactNameSent;
    private String contactName;
    private boolean contactDetailsSent;
    private String contactDetails;
    private boolean paymentNotesSent;
    private String paymentNotes;
    private boolean activeSent;
    private Boolean active;
    private boolean versionSent;
    private Long version;

    @Schema(description = "A new name. Unique among live suppliers, ignoring letter case. Cannot be empty.",
            example = "Example Trading Ltd", maxLength = 120)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.nameSent = true;
    }

    @Schema(description = "A new country, as an ISO 3166-1 alpha-2 code. Cannot be empty.",
            example = "CN", minLength = 2, maxLength = 2)
    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
        this.countrySent = true;
    }

    @Schema(description = "Who you deal with there. Send null or blank text to clear it.",
            example = "A. Contact", maxLength = 120, types = {"string", "null"})
    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
        this.contactNameSent = true;
    }

    @Schema(description = "How to reach them. Send null or blank text to clear it.",
            example = "WhatsApp +86 000 0000", maxLength = 500, types = {"string", "null"})
    public String getContactDetails() {
        return contactDetails;
    }

    public void setContactDetails(String contactDetails) {
        this.contactDetails = contactDetails;
        this.contactDetailsSent = true;
    }

    @Schema(description = "How you pay them, in words. Send null or blank text to clear it. A description, "
            + "not a payment record.", example = "Bank transfer, in full, on shipping", maxLength = 500,
            types = {"string", "null"})
    public String getPaymentNotes() {
        return paymentNotes;
    }

    public void setPaymentNotes(String paymentNotes) {
        this.paymentNotes = paymentNotes;
        this.paymentNotesSent = true;
    }

    @Schema(description = "false: Oriah has stopped using this supplier; true: using it again. Cannot be empty.")
    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
        this.activeSent = true;
    }

    @Schema(description = "The version of the supplier this edit was made from (the version in the response "
            + "you loaded). If it is no longer current, someone else saved first and this edit is refused "
            + "with 409. Leave it out to skip the check: the last save then wins.", example = "3", minimum = "0")
    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
        this.versionSent = true;
    }

    // Was the field in the body at all? Not bean getters (no get/is prefix), so they are not part of the API.

    public boolean hasName() {
        return nameSent;
    }

    public boolean hasCountry() {
        return countrySent;
    }

    public boolean hasContactName() {
        return contactNameSent;
    }

    public boolean hasContactDetails() {
        return contactDetailsSent;
    }

    public boolean hasPaymentNotes() {
        return paymentNotesSent;
    }

    public boolean hasActive() {
        return activeSent;
    }

    public boolean hasVersion() {
        return versionSent;
    }
}
