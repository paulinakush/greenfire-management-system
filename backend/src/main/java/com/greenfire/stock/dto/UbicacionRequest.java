package com.greenfire.stock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UbicacionRequest(@NotBlank @Size(max = 120) String nombre) {
}
