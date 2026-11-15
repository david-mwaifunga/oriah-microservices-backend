package com.oriah.shipping.controller;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.shipping.TestcontainersConfiguration;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Delivery prices over HTTP (Phase 7). Admin keeps them under {@code /api/v1/admin/shipping}, for SHIPPING_ADMIN only (a job role such as a
 * packer or a bookkeeper holds capabilities, not USER, and must be refused the shopper's endpoints and the admin ones alike unless it holds
 * the capability). A shopper reads what is offered and the quote under {@code /api/v1/shipping}, needing USER. Every refusal is a problem with
 * a stable name a screen can switch on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class DeliveryHttpTest {

    private static final String ADMIN = "/api/v1/admin/shipping";
    private static final String SHOP = "/api/v1/shipping";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor shippingAdmin() {
        return jwt().jwt(j -> j.subject("kc-maya")).authorities(new SimpleGrantedAuthority("ROLE_SHIPPING_ADMIN"));
    }

    private static RequestPostProcessor shopper() {
        return jwt().jwt(j -> j.subject("kc-alice")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor packer() {
        return jwt().jwt(j -> j.subject("kc-pat")).authorities(new SimpleGrantedAuthority("ROLE_FULFILMENT_AGENT"));
    }

    private String json(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private String field(MvcResult r, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(json(r), path).toString();
    }

    private UUID copperbelt() throws Exception {
        MvcResult r = mvc.perform(get(ADMIN + "/provinces").with(shippingAdmin())).andExpect(status().isOk()).andReturn();
        return UUID.fromString(field(r, "$[?(@.name=='Copperbelt')].id").replaceAll("[\\[\\]\"]", ""));
    }

    private MvcResult newTown() throws Exception {
        return mvc.perform(post(ADMIN + "/towns").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provinceId\":\"" + copperbelt() + "\",\"name\":\"Http Town " + UUID.randomUUID().toString().substring(0, 8) + "\"}"))
                .andExpect(status().isCreated()).andReturn();
    }

    // ---------------------------------------------------------------- who may

    @Test
    @DisplayName("only SHIPPING_ADMIN keeps prices: a shopper and a packer are refused, and so is a call with no token")
    void adminIsForShippingAdminOnly() throws Exception {
        for (String path : new String[] {"/provinces", "/towns", "/options", "/prices", "/settings"}) {
            mvc.perform(get(ADMIN + path).with(shopper())).andExpect(status().isForbidden());
            mvc.perform(get(ADMIN + path).with(packer())).andExpect(status().isForbidden());
            mvc.perform(get(ADMIN + path)).andExpect(status().isUnauthorized());
            mvc.perform(get(ADMIN + path).with(shippingAdmin())).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("what is offered and the quote are the shopper's: USER only, so a job role without USER is refused")
    void shopIsForShoppers() throws Exception {
        mvc.perform(get(SHOP + "/towns").with(shopper())).andExpect(status().isOk());
        mvc.perform(get(SHOP + "/towns").with(shippingAdmin())).andExpect(status().isForbidden());
        mvc.perform(get(SHOP + "/towns")).andExpect(status().isUnauthorized());
        mvc.perform(post(SHOP + "/quote").with(packer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionCode\":\"SELF_COLLECT\",\"goodsTotal\":100.00}")).andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- admin: towns, options

    @Test
    @DisplayName("a town is added (201), switched off under its version, and a stale switch is a 409 that names the current version")
    void towns() throws Exception {
        MvcResult added = newTown();
        String id = field(added, "$.id");
        org.assertj.core.api.Assertions.assertThat(field(added, "$.active")).isEqualTo("true");
        org.assertj.core.api.Assertions.assertThat(field(added, "$.version")).isEqualTo("0");

        mvc.perform(patch(ADMIN + "/towns/" + id).with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.version").value(1));
        mvc.perform(patch(ADMIN + "/towns/" + id).with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true,\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/stale-version"))
                .andExpect(jsonPath("$.currentVersion").value(1));
        mvc.perform(patch(ADMIN + "/towns/" + UUID.randomUUID()).with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true,\"version\":0}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/town-not-found"));
        mvc.perform(delete(ADMIN + "/towns/" + id).with(shippingAdmin())).andExpect(status().is4xxClientError());
        mvc.perform(get(ADMIN + "/towns").with(shippingAdmin()).param("active", "false")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an option is added (a bus company's parcel service) and its code is made from its name; a duplicate is 409 `option-exists`; an unknown kind is 400")
    void options() throws Exception {
        String name = "Http Bus " + UUID.randomUUID().toString().substring(0, 6);

        mvc.perform(post(ADMIN + "/options").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"kind\":\"BUS_PARCEL\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(startsWith("HTTP_BUS_"))).andExpect(jsonPath("$.active").value(true));
        mvc.perform(post(ADMIN + "/options").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\",\"kind\":\"BUS_PARCEL\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/option-exists"));
        mvc.perform(post(ADMIN + "/options").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A Rocket\",\"kind\":\"ROCKET\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- admin: prices, end to end

    @Test
    @DisplayName("a price is drafted, edited, published and then offered and quoted; an edit of a published price is refused; a discarded draft is gone")
    void aPriceFromDraftToQuote() throws Exception {
        String town = field(newTown(), "$.id");
        MvcResult draft = mvc.perform(post(ADMIN + "/prices").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"YANGO\",\"amount\":80.00,\"allowsOriahCollects\":true,\"allowsRecipientPays\":true,\"etaDaysMin\":1,\"etaDaysMax\":2}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn();
        String price = field(draft, "$.id");

        mvc.perform(get(SHOP + "/towns").with(shopper())).andExpect(status().isOk()).andExpect(jsonPath("$.provinces[*].towns[?(@.id=='" + town + "')]", hasSize(0)));
        mvc.perform(put(ADMIN + "/prices/" + price).with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":85.00,\"allowsOriahCollects\":true,\"allowsRecipientPays\":true,\"etaDaysMin\":1,\"etaDaysMax\":2,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(85.00)).andExpect(jsonPath("$.version").value(1));
        mvc.perform(post(ADMIN + "/prices/" + price + "/publish").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.publishedBy").value("user:kc-maya"));

        mvc.perform(get(SHOP + "/towns").with(shopper())).andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("ZMW"))
                .andExpect(jsonPath("$.selfCollect.code").value("SELF_COLLECT"))
                .andExpect(jsonPath("$.provinces[*].towns[?(@.id=='" + town + "')].options[0].code").value("YANGO"))
                .andExpect(jsonPath("$.provinces[*].towns[?(@.id=='" + town + "')].options[0].amount").value(85.00));
        mvc.perform(post(SHOP + "/quote").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"YANGO\",\"goodsTotal\":1200.00,\"currency\":\"ZMW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(85.00))
                .andExpect(jsonPath("$.payer").value("ORIAH_COLLECTS"))
                .andExpect(jsonPath("$.priceId").value(price))
                .andExpect(jsonPath("$.freeByThreshold").value(false));
        mvc.perform(post(SHOP + "/quote").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"YANGO\",\"payer\":\"RECIPIENT_PAYS\",\"goodsTotal\":1200.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(0.00)).andExpect(jsonPath("$.listAmount").value(85.00));

        mvc.perform(put(ADMIN + "/prices/" + price).with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1.00,\"allowsOriahCollects\":true,\"allowsRecipientPays\":false,\"version\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/price-published"));
        mvc.perform(get(ADMIN + "/prices").with(shippingAdmin()).param("townId", town)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("a draft can be discarded (204) under its version; a price must be whole ngwee, a fraction where a whole number belongs is a 400")
    void drafts() throws Exception {
        String town = field(newTown(), "$.id");
        MvcResult draft = mvc.perform(post(ADMIN + "/prices").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"EMS\",\"amount\":40.00}")).andExpect(status().isCreated()).andReturn();
        String price = field(draft, "$.id");

        mvc.perform(post(ADMIN + "/prices").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"EMS\",\"amount\":41.00}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/draft-exists")).andExpect(jsonPath("$.priceId").value(price));
        mvc.perform(post(ADMIN + "/prices").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"PLATINUM\",\"amount\":12.345}")).andExpect(status().isBadRequest());
        mvc.perform(post(ADMIN + "/prices").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"PLATINUM\",\"amount\":10.00,\"etaDaysMin\":1.9,\"etaDaysMax\":3}")).andExpect(status().isBadRequest());
        mvc.perform(delete(ADMIN + "/prices/" + price).with(shippingAdmin()).param("version", "7")).andExpect(status().isConflict());
        mvc.perform(delete(ADMIN + "/prices/" + price).with(shippingAdmin()).param("version", "0")).andExpect(status().isNoContent());
        mvc.perform(get(ADMIN + "/prices").with(shippingAdmin()).param("townId", town)).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Oriah's own delivery cannot be offered to a recipient who pays (Q27): a draft or an edit that tries is 409 own-delivery-is-oriah-collects and changes nothing")
    void ownDeliveryIsNeverRecipientPaid() throws Exception {
        String town = field(newTown(), "$.id");

        mvc.perform(post(ADMIN + "/prices").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"OWN_DELIVERY\",\"amount\":45.00,\"allowsRecipientPays\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/own-delivery-is-oriah-collects"));
        mvc.perform(get(ADMIN + "/prices").with(shippingAdmin()).param("townId", town)).andExpect(jsonPath("$", hasSize(0)));

        MvcResult draft = mvc.perform(post(ADMIN + "/prices").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"OWN_DELIVERY\",\"amount\":45.00}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.allowsRecipientPays").value(false)).andReturn();
        String price = field(draft, "$.id");
        mvc.perform(put(ADMIN + "/prices/" + price).with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":45.00,\"allowsRecipientPays\":true,\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("https://oriah.dev/problems/own-delivery-is-oriah-collects"));
        mvc.perform(get(ADMIN + "/prices").with(shippingAdmin()).param("townId", town)).andExpect(jsonPath("$[0].allowsRecipientPays").value(false))
                .andExpect(jsonPath("$[0].version").value(0));
    }

    // ---------------------------------------------------------------- admin: the threshold

    @Test
    @DisplayName("the free-delivery threshold is set and switched off under its version; every change is listed with who and why")
    void threshold() throws Exception {
        MvcResult now = mvc.perform(get(ADMIN + "/settings").with(shippingAdmin())).andExpect(status().isOk()).andReturn();
        long version = Long.parseLong(field(now, "$.version"));

        mvc.perform(put(ADMIN + "/settings").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"freeDeliveryThreshold\":2000.00,\"version\":" + version + ",\"reason\":\"Independence Day week\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.freeDeliveryThreshold").value(2000.00)).andExpect(jsonPath("$.version").value(version + 1));
        mvc.perform(put(ADMIN + "/settings").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"freeDeliveryThreshold\":0.00,\"version\":" + (version + 1) + "}")).andExpect(status().isBadRequest());
        mvc.perform(put(ADMIN + "/settings").with(shippingAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"freeDeliveryThreshold\":null,\"version\":" + (version + 1) + ",\"reason\":\"week over\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.freeDeliveryThreshold").doesNotExist());
        mvc.perform(get(ADMIN + "/settings/changes").with(shippingAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reason").value("week over")).andExpect(jsonPath("$[0].changedBy").value("user:kc-maya"));
    }

    // ---------------------------------------------------------------- the shopper's refusals

    @Test
    @DisplayName("a town not yet priced is `town-not-offered`, never `outside the delivery area`; an unknown town is 404; Dollars and a negative total are 400")
    void theShoppersRefusals() throws Exception {
        String town = field(newTown(), "$.id");

        mvc.perform(post(SHOP + "/quote").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"YANGO\",\"goodsTotal\":100.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://oriah.dev/problems/town-not-offered"))
                .andExpect(jsonPath("$.townId").value(town));
        mvc.perform(post(SHOP + "/quote").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + UUID.randomUUID() + "\",\"optionCode\":\"YANGO\",\"goodsTotal\":100.00}")).andExpect(status().isNotFound());
        mvc.perform(post(SHOP + "/quote").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"YANGO\",\"goodsTotal\":100.00,\"currency\":\"USD\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(SHOP + "/quote").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"townId\":\"" + town + "\",\"optionCode\":\"YANGO\",\"goodsTotal\":-5.00}")).andExpect(status().isBadRequest());
        mvc.perform(post(SHOP + "/quote").with(shopper()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionCode\":\"SELF_COLLECT\",\"goodsTotal\":100.00}")).andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(0.00));
    }
}
