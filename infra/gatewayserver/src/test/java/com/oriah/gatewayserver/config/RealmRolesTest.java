package com.oriah.gatewayserver.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role set built now for hiring (MVP_PLAN Phase 0, item 5;
 * CLIENT_DECISIONS.md section 1.3), read from the realm files themselves.
 *
 * <h2>Two kinds of role</h2>
 *
 * <b>Capability roles</b> are what the gateway and the services check
 * ({@code hasRole('CATALOG_ADMIN')}). <b>Job roles</b> are what a person is
 * given -- Keycloak composites of capabilities -- so taking on a hire means
 * assigning one job role, with no realm change. Keycloak puts a composite's
 * members in the token's {@code realm_access.roles}, so the code only ever
 * checks capabilities.
 *
 * <p>Checks that only prove a role NAME exists would pass a wrong composite:
 * a MANAGER that could write off stock, a SALES assistant who could read cost.
 * So each job role's EFFECTIVE capability set -- composites expanded -- is
 * asserted exactly.
 *
 * <h2>Production and development identities are split</h2>
 *
 * The committed realm ({@code keycloak/import/oriah-realm.json}) is what
 * production imports: every role, both clients, the machine identity, and no
 * person and no secret. The test users the smoke test and Playwright sign in
 * as live in a development-only users file ({@code keycloak/dev/
 * oriah-users-0.json}), which the dev stack mounts beside it; Keycloak
 * imports it with the realm.
 */
class RealmRolesTest {

    /** Every capability role, as section 1.3 defines them. */
    static final Set<String> CAPABILITIES = Set.of(
            "CATALOG_ADMIN", "INVENTORY_ADMIN", "CUSTOMER_ADMIN", "ORDER_ADMIN", "PAYMENT_ADMIN",
            "PROCUREMENT_ADMIN", "FINANCE_ADMIN", "SHIPPING_ADMIN", "SALES_AGENT", "FULFILMENT_AGENT",
            "COST_VIEWER", "STOCK_WRITE_OFF", "BACKDATE_SALE", "DELIVERY_WAIVER");

    /** The job roles a person is given, and exactly what each one may do. */
    static final Map<String, Set<String>> JOB_ROLES = jobRoles();

    private static Map<String, Set<String>> jobRoles() {
        Map<String, Set<String>> roles = new LinkedHashMap<>();
        roles.put("OWNER", CAPABILITIES);
        Set<String> manager = new TreeSet<>(CAPABILITIES);
        // Write-offs are owner-only (Q8); a late-dated sale past 72 hours is
        // owner-only by design (section 8.2); refunds and the books stay with
        // the owner.
        manager.removeAll(Set.of("STOCK_WRITE_OFF", "BACKDATE_SALE", "PAYMENT_ADMIN", "FINANCE_ADMIN"));
        roles.put("MANAGER", manager);
        // No cost or margin for a sales assistant (Q29).
        roles.put("SALES", Set.of("SALES_AGENT"));
        roles.put("DISPATCH", Set.of("FULFILMENT_AGENT"));
        roles.put("BOOKKEEPER", Set.of("FINANCE_ADMIN", "COST_VIEWER"));
        return roles;
    }

    /** What a shopper holds. Not a capability: the customer surface, /me endpoints. */
    private static final String USER = "USER";

    private static JsonNode realm;
    private static JsonNode devUsers;
    private static String realmText;

    @BeforeAll
    static void load() throws IOException {
        Path root = repositoryRoot();
        ObjectMapper json = new ObjectMapper();
        realmText = Files.readString(root.resolve("keycloak/import/oriah-realm.json"));
        realm = json.readTree(realmText);
        devUsers = json.readTree(Files.readString(root.resolve("keycloak/dev/oriah-users-0.json")));
    }

    /** oriah-api's root, found by walking up from wherever the build runs the test. */
    private static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve("keycloak/import/oriah-realm.json"))) {
                return dir;
            }
        }
        throw new IllegalStateException("keycloak/import/oriah-realm.json not found above " + Path.of("").toAbsolutePath());
    }

    // ------------------------------------------------------------------ roles

    private static Map<String, JsonNode> realmRoles() {
        return StreamSupport.stream(realm.path("roles").path("realm").spliterator(), false)
                .collect(Collectors.toMap(r -> r.path("name").asText(), r -> r, (a, b) -> a, LinkedHashMap::new));
    }

    /** Every capability a role grants, its composites expanded all the way down. */
    private static Set<String> effectiveCapabilities(String role) {
        Map<String, JsonNode> roles = realmRoles();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(role));
        while (!todo.isEmpty()) {
            String next = todo.pop();
            if (!seen.add(next)) {
                continue;
            }
            JsonNode node = roles.get(next);
            if (node == null) {
                continue;
            }
            node.path("composites").path("realm").forEach(member -> todo.push(member.asText()));
        }
        seen.retainAll(CAPABILITIES);
        return new TreeSet<>(seen);
    }

    @Test
    @DisplayName("every capability role is declared, and none is a composite")
    void everyCapabilityIsDeclared() {
        Map<String, JsonNode> roles = realmRoles();

        assertThat(roles.keySet()).containsAll(CAPABILITIES);
        for (String capability : CAPABILITIES) {
            assertThat(roles.get(capability).path("composite").asBoolean())
                    .as("%s is a capability the code checks, not a bundle of others", capability)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("each job role grants EXACTLY its capabilities, composites expanded -- not merely a role that exists")
    void eachJobRoleGrantsExactlyItsCapabilities() {
        Map<String, JsonNode> roles = realmRoles();

        JOB_ROLES.forEach((job, expected) -> {
            assertThat(roles).as("job role %s", job).containsKey(job);
            assertThat(roles.get(job).path("composite").asBoolean()).as("%s is a composite", job).isTrue();
            assertThat(effectiveCapabilities(job))
                    .as("%s's capabilities", job)
                    .containsExactlyInAnyOrderElementsOf(expected);
        });
    }

    @Test
    @DisplayName("a job role bundles capabilities only -- never USER, never another job role")
    void jobRolesBundleCapabilitiesOnly() {
        Map<String, JsonNode> roles = realmRoles();

        for (String job : JOB_ROLES.keySet()) {
            assertThat(roles).as("job role %s", job).containsKey(job);
            Set<String> members = new TreeSet<>();
            roles.get(job).path("composites").path("realm").forEach(m -> members.add(m.asText()));
            assertThat(CAPABILITIES).as("%s's direct members", job).containsAll(members);
            assertThat(roles.get(job).path("composites").has("client"))
                    .as("%s bundles no client role", job).isFalse();
        }
    }

    // ----------------------------------------------------------- the machine

    private static List<JsonNode> users(JsonNode file) {
        return StreamSupport.stream(file.path("users").spliterator(), false).toList();
    }

    private static Set<String> realmRolesOf(JsonNode user) {
        Set<String> roles = new TreeSet<>();
        user.path("realmRoles").forEach(r -> roles.add(r.asText()));
        return roles;
    }

    @Test
    @DisplayName("the committed realm carries no person: its only user is the machine identity")
    void theCommittedRealmCarriesNoPerson() {
        assertThat(users(realm))
                .extracting(u -> u.path("username").asText())
                .containsExactly("service-account-oriah-admin");
    }

    @Test
    @DisplayName("the machine identity holds exactly USER and INVENTORY_ADMIN -- what its callers need, never a job role")
    void theMachineIdentityHoldsExactlyWhatItsCallersNeed() {
        // USER: order's server-side shipping quote (item 4). INVENTORY_ADMIN:
        // checkout's reservations (Phase 7). A later machine caller adds its
        // role in its own phase, with a test.
        JsonNode account = users(realm).getFirst();

        assertThat(account.path("serviceAccountClientId").asText()).isEqualTo("oriah-admin");
        assertThat(realmRolesOf(account)).containsExactlyInAnyOrder(USER, "INVENTORY_ADMIN");
        assertThat(account.has("credentials")).isFalse();
    }

    @Test
    @DisplayName("every client with a service account has its user declared, so none is created with default roles")
    void everyServiceAccountIsDeclared() {
        // Keycloak creates a missing service-account user itself, holding
        // default-roles-oriah -- roles nobody chose and no test would see.
        Set<String> declared = users(realm).stream()
                .map(u -> u.path("serviceAccountClientId").asText())
                .collect(Collectors.toSet());

        realm.path("clients").forEach(client -> {
            if (client.path("serviceAccountsEnabled").asBoolean()) {
                assertThat(declared).as("service account for %s", client.path("clientId").asText())
                        .contains(client.path("clientId").asText());
            }
        });
    }

    // ------------------------------------------------------------ the secret

    @Test
    @DisplayName("no client secret is committed: oriah-admin's comes from the environment, with no default")
    void noClientSecretIsCommitted() {
        JsonNode admin = StreamSupport.stream(realm.path("clients").spliterator(), false)
                .filter(c -> "oriah-admin".equals(c.path("clientId").asText()))
                .findFirst().orElseThrow();

        // Keycloak replaces ${VAR} from its environment at import. A default
        // (${VAR:value}) would commit the value in clear; ${env.VAR} is not
        // replaced at all and would become the literal secret.
        assertThat(admin.path("secret").asText()).isEqualTo("${ORIAH_ADMIN_CLIENT_SECRET}");
        assertThat(realmText).doesNotContain("oriah-admin-secret");
        assertThat(realmText).doesNotContainPattern("\\$\\{[A-Z_]+:");
        assertThat(realmText).doesNotContain("${env.");
    }

    @Test
    @DisplayName("no credential of any kind is committed")
    void noCredentialIsCommitted() {
        assertThat(realmText).doesNotContain("\"credentials\"");
        assertThat(realmText).doesNotContain("\"password\"");
    }

    // ------------------------------------------------------- the dev overlay

    @Test
    @DisplayName("the dev users are for the oriah realm, in Keycloak's own users-file format")
    void theDevOverlayIsAUsersFile() {
        assertThat(devUsers.path("realm").asText()).isEqualTo("oriah");
        // A users file declares people and nothing else: a second realm file
        // would REPLACE the committed realm on first import, not add to it.
        assertThat(devUsers.has("roles")).isFalse();
        assertThat(devUsers.has("clients")).isFalse();
    }

    @Test
    @DisplayName("no person holds a capability role directly -- only job roles, and USER")
    void noPersonHoldsACapabilityDirectly() {
        for (JsonNode user : users(devUsers)) {
            assertThat(realmRolesOf(user))
                    .as("%s", user.path("username").asText())
                    .doesNotContainAnyElementsOf(CAPABILITIES);
            assertThat(JOB_ROLES.keySet().stream().filter(realmRolesOf(user)::contains).count()
                            + (realmRolesOf(user).contains(USER) ? 1 : 0))
                    .as("%s holds only declared job roles and USER", user.path("username").asText())
                    .isEqualTo(realmRolesOf(user).size());
        }
    }

    @Test
    @DisplayName("the smoke test's and Playwright's people: an OWNER who also shops, the first-login owner, a customer")
    void theDevPeopleAreTheOnesTheTestsSignInAs() {
        Map<String, JsonNode> byName = users(devUsers).stream()
                .collect(Collectors.toMap(u -> u.path("username").asText(), u -> u));
        assertThat(byName).containsKeys("maya", "admin", "alice");

        // maya: every admin surface, AND a second customer in the smoke test's
        // money-path probes -- so USER as well. Imported users get no default
        // roles, so USER is listed.
        assertThat(realmRolesOf(byName.get("maya"))).containsExactlyInAnyOrder("OWNER", USER);
        // admin@oriah.local: the owner's first sign-in, forced to set a password.
        assertThat(realmRolesOf(byName.get("admin"))).containsExactlyInAnyOrder("OWNER", USER);
        assertThat(byName.get("admin").path("requiredActions").toString()).contains("UPDATE_PASSWORD");
        // alice: a customer and nothing else.
        assertThat(realmRolesOf(byName.get("alice"))).containsExactly(USER);
    }

    @Test
    @DisplayName("the dev people keep their ids, so a Keycloak reset orphans no customer, cart or order")
    void theDevPeopleHavePinnedIds() {
        // Every service keys its rows by the token's sub. Without a pinned id
        // a reset mints a new one, and alice's profile, cart and orders belong
        // to nobody.
        for (JsonNode user : users(devUsers)) {
            assertThat(user.path("id").asText())
                    .as("%s's id", user.path("username").asText())
                    .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        }
    }

    @Test
    @DisplayName("every role a dev person holds is declared in the committed realm")
    void everyDevRoleIsDeclared() {
        Set<String> declared = realmRoles().keySet();

        for (JsonNode user : users(devUsers)) {
            assertThat(declared).as("%s's roles", user.path("username").asText())
                    .containsAll(realmRolesOf(user));
        }
    }
}
