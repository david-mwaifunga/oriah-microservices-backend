package com.oriah.finance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import lombok.extern.slf4j.Slf4j;

/**
 * Finance (Phase 10): what the shop really made. It reads what every other service says happened (sales, money, stock, bills, prices) from their event logs,
 * keeps it in an append-only ledger, takes expenses and taxes typed in by hand, and reports each month's profit. It calls no service and produces no event.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Slf4j
public class FinanceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FinanceServiceApplication.class, args);
        log.info("finance-service ready");
    }
}
