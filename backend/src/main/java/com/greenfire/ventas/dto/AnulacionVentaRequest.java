package com.greenfire.ventas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnulacionVentaRequest(@NotBlank @Size(max = 500) String motivo) {
}
