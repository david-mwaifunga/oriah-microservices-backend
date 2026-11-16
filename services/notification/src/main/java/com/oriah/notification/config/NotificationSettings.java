package com.oriah.notification.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.oriah.notification.model.MessageKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Notification's own settings ({@code oriah.notification.*}): every timing and limit, each with its default in the service's own
 * {@code application.yaml}, so the service starts with the Config Server away. Bound once and immutable.
 *
 * <p><b>It holds no secret, no recipient and no live mail server.</b> Whether the stack is live, the mail account, the live From address, who
 * receives alerts, the support line and the pause switch come from the process environment only ({@link ProcessEnv}, {@link EmailModeGuard}):
 * a property can be set by the config repository that every stack shares.
 *
 * <p>Rules that span two keys (a timeout must fit inside the longest send; the lease must outlast it) are checked by {@link EmailModeGuard} when
 * the service starts, with a message that names the key.
 *
 * @param zone    the shop's clock: times written in a message
 * @param siteUrl the storefront's address for the link at the foot of a customer's email; empty for no link
 */
@ConfigurationProperties("oriah.notification")
@Validated
public record NotificationSettings(@NotNull ZoneId zone, String siteUrl, @NotNull @Valid Shop shop, @NotNull @Valid Email email,
                                   @NotNull @Valid Send send, @NotNull @Valid Alerts alerts, @NotNull @Valid BelowCost belowCost) {

    /**
     * The below-cost check.
     *
     * @param enabled          its switch (the test configuration turns it off and calls the poller itself)
     * @param delay            milliseconds between two rounds
     * @param initialDelay     milliseconds before the first round (the services catalog asks need time to start)
     * @param clearAfterRounds good rounds in a row without a piece before its alert ends
     * @param quietFor         a piece the owner was emailed about this recently is not emailed again until the time is up
     * @param digestWindow     how long a digest email is worth sending; a later round writes a fresh one
     * @param downAfter        no good round for this long is itself an alert ("the below-cost check is not running")
     * @param maxNamed         how many pieces one email names; the rest are counted
     */
    public record BelowCost(boolean enabled, @Min(1000) long delay, @Min(0) long initialDelay, @Min(1) int clearAfterRounds,
                            @NotNull Duration quietFor, @NotNull Duration digestWindow, @NotNull Duration downAfter, @Min(1) int maxNamed) {
    }


    /** The shop's own public details, written by the owner; each is left out of a message while it is empty. */
    public record Shop(String payLine, String collectLine, String contactLine) {
    }

    /**
     * @param transport   while not live: {@code none} or {@code catcher}; ignored when live
     * @param fromAddress the From address while not live; it must be on a name reserved for testing
     * @param fromName    the name shown beside the From address, in every mode
     * @param emailed     the kinds of message that are emailed as well as shown on the website
     */
    public record Email(@NotBlank String transport, @NotNull @Valid Catcher catcher, @NotBlank String fromAddress, @NotBlank String fromName,
                        @NotNull @Valid Smtp smtp, @NotNull Set<MessageKind> emailed) {
    }

    /** The local mail catcher; its host must be one of four fixed names. */
    public record Catcher(@NotBlank String host, @Min(1) @Max(65535) int port) {
    }

    /** How long to wait for the connection, for each reply, and for one write. */
    public record Smtp(@NotNull Duration connectTimeout, @NotNull Duration readTimeout, @NotNull Duration writeTimeout) {
    }

    /**
     * @param enabled            the dispatcher's switch (the test configuration turns it off and calls the dispatcher itself)
     * @param delay              milliseconds between two passes of the dispatcher
     * @param initialDelay       milliseconds before its first pass
     * @param batchSize          how many waiting messages one pass looks at
     * @param maxSend            the one wall-clock limit on a whole send
     * @param lease              how long a message being sent is out of reach of any other sender
     * @param backoff            the wait after the 1st, 2nd, 3rd ... failed try; the last one repeats
     * @param serverQuiet        after the mail server itself failed, nothing is handed to it for this long; it doubles with each failure in a row
     * @param serverQuietMax     the longest such quiet time
     * @param window             how long after its event was recorded a message is still worth emailing
     * @param backdatedStepAfter a dispatch step keyed this much later than it happened is not emailed: the customer already has the parcel
     * @param addressRecheck     how soon a message whose customer's address is not known yet is looked at again
     */
    public record Send(boolean enabled, @Min(100) long delay, @Min(0) long initialDelay, @Min(1) @Max(500) int batchSize,
                       @NotNull Duration maxSend, @NotNull Duration lease, @NotEmpty List<Duration> backoff, @NotNull Duration serverQuiet,
                       @NotNull Duration serverQuietMax, @NotNull Duration window, @NotNull Duration backdatedStepAfter,
                       @NotNull Duration addressRecheck) {
    }

    /**
     * @param maxFactAge  an alert fact recorded longer ago than this still updates what is known but emails nobody (history read late)
     * @param retellAfter an open alert that no mail server ever took is written again this often
     * @param retellMax   and at most this many times
     * @param downAfter   the mail server failing this long is itself an alert ("emails are not going out")
     * @param listUnansweredFor how long money recorded whose sale was never heard to complete stays in the default list of alerts after its
     *                          alert closed by the clock
     * @param settleDelay the wait before the first "money recorded but the sale has not completed" email and before any "sorted" email: an
     *                    answer that is only seconds behind the alarm then mails nobody
     */
    public record Alerts(@NotNull @Valid SandboxRecipients sandboxRecipients, @NotNull Duration maxFactAge, @NotNull Duration retellAfter,
                         @Min(0) int retellMax, @NotNull Duration downAfter, @NotNull Duration listUnansweredFor, @NotNull Duration settleDelay,
                         @NotNull @Valid PaymentToConfirm paymentToConfirm, @NotNull @Valid Hold hold, @NotNull @Valid Settlement settlement,
                         @NotNull @Valid VoidAfterSale voidAfterSale) {
    }

    /**
     * Money recorded whose sale has not completed.
     *
     * @param remindAfter how long after the first notice staff are told again if it is still open
     * @param remindEvery and how often after that
     * @param closeAfter  when the alert closes by itself, with a last note (a sale can wait this long for a box to be received)
     */
    public record Settlement(@NotNull Duration remindAfter, @NotNull Duration remindEvery, @NotNull Duration closeAfter) {
    }

    /** @param openFor how long "a cancelled payment record paid a sale anyway" stays open: no fact ends it, a person puts the books right */
    public record VoidAfterSale(@NotNull Duration openFor) {
    }

    /** @param maxAlertsEmailedPerDay how many payment-claim alerts are emailed in any 24 hours; beyond it they are listed only */
    public record PaymentToConfirm(@Min(1) int maxAlertsEmailedPerDay) {
    }

    /**
     * When staff are reminded of a payment claim that is still not confirmed.
     *
     * @param remindBefore how long before the pieces are released
     * @param quietFrom    a reminder that would fall from this hour ...
     * @param quietUntil   ... to this hour (the shop's clock) moves back to quietFrom
     * @param closeGrace   how long after the hold's end the alert closes by itself, if no event closed it
     */
    public record Hold(@NotNull Duration remindBefore, @NotNull java.time.LocalTime quietFrom, @NotNull java.time.LocalTime quietUntil,
                       @NotNull Duration closeGrace) {
    }

    /** Alert mailboxes for a developer's own machine: used only while not live and only when the environment gives none. */
    public record SandboxRecipients(String money, String cost) {
    }
}
