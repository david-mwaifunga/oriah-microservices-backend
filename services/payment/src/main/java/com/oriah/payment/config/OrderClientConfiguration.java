package com.oriah.payment.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

import com.oriah.payment.client.OrderClient;

/** Payment's one Feign client: order, asked to take a payment attempt before ZynlePay is asked for money (Phase 13). */
@Configuration
@EnableFeignClients(clients = OrderClient.class)
public class OrderClientConfiguration {
}
