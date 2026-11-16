package com.oriah.notification.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.oriah.notification.model.MessageKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
                                   @NotNull @Valid Send send, @NotNull @Valid Alerts alerts) {

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
     * @param maxSend            the one wall-clock limit on a whole send
     * @param lease              how long a message being sent is out of reach of any other sender
     * @param window             how long after its event was recorded a message is still worth emailing
     * @param backdatedStepAfter a dispatch step keyed this much later than it happened is not emailed: the customer already has the parcel
     */
    public record Send(@NotNull Duration maxSend, @NotNull Duration lease, @NotNull Duration window, @NotNull Duration backdatedStepAfter) {
    }

    public record Alerts(@NotNull @Valid SandboxRecipients sandboxRecipients) {
    }

    /** Alert mailboxes for a developer's own machine: used only while not live and only when the environment gives none. */
    public record SandboxRecipients(String money, String cost) {
    }
}
