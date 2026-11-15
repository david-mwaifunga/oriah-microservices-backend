package com.oriah.finance.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** The producers that will be read again from the beginning, and when. */
@Schema(name = "Reverify")
public record ReverifyResponse(List<String> producers, String takesEffect) {
}
