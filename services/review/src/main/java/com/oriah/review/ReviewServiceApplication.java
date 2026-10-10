package com.oriah.review;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
// Registers com.oriah.review.dto.ReviewContactInfoDto. Matches catalog's
// precedent rather than the course's
// @EnableConfigurationProperties(X.class), which has to be re-edited for
// every future properties class.
@ConfigurationPropertiesScan
@Slf4j
public class ReviewServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReviewServiceApplication.class, args);
        log.info("review-service ready");
    }
}
