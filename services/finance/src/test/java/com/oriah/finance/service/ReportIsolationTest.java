package com.oriah.finance.service;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** A report asks the database many questions; they must all see the same moment, or a figure and the table that must add up to it can disagree because a row landed between them. */
class ReportIsolationTest {

    @Test
    @DisplayName("every read of the profit report runs in one repeatable-read snapshot")
    void oneSnapshot() {
        Transactional onClass = ProfitReportService.class.getAnnotation(Transactional.class);
        assertThat(onClass).isNotNull();
        assertThat(onClass.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        for (Method m : ProfitReportService.class.getDeclaredMethods()) {
            Transactional own = m.getAnnotation(Transactional.class);
            if (own != null) {
                assertThat(own.isolation()).as(m.getName()).isEqualTo(Isolation.REPEATABLE_READ);
            }
        }
    }
}
