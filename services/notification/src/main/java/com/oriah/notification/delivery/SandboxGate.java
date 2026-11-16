package com.oriah.notification.delivery;

import java.util.List;

import com.oriah.notification.contact.EmailAddressRule;

/**
 * Which addresses a stack that is NOT production may hand to a mail server: only those on a name that can never be a real mailbox
 * ({@code oriah.local}, or any name ending {@code .local}, {@code .test}, {@code .example}, {@code .invalid}, {@code .localhost}: the endings
 * reserved for testing).
 *
 * <p>This is the second lock. The first is that such a stack can only talk to the local mail catcher. Development and test data hold addresses and
 * phone numbers that look real, so "it only goes to the catcher" is not left as the one thing between a test and a stranger's mailbox.
 *
 * <p>{@code example.com} is NOT on the list on purpose: it stands for "a real-looking address" in the live proof that nothing leaves.
 */
public final class SandboxGate {

    private static final List<String> NEVER_A_REAL_MAILBOX = List.of(".local", ".test", ".example", ".invalid", ".localhost");

    private SandboxGate() {
    }

    public static boolean mayLeaveASandbox(String address) {
        return EmailAddressRule.oneAddress(address)
                .map(EmailAddressRule::domainOf)
                .filter(domain -> NEVER_A_REAL_MAILBOX.stream().anyMatch(domain::endsWith))
                .isPresent();
    }
}
