package com.github.galpiii.galpi.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginCodeExchangeRequest(@NotBlank String code) {
}
