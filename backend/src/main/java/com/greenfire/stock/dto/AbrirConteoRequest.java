package com.greenfire.stock.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AbrirConteoRequest(@NotNull Long ubicacionId, @Size(max = 2000) String observaciones) {
}
