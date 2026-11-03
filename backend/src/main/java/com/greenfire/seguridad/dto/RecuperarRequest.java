package com.greenfire.seguridad.dto;

import jakarta.validation.constraints.NotBlank;

public record RecuperarRequest(@NotBlank String email) {
}
