package com.greenfire.entregas.dto;

import com.greenfire.entregas.ResolucionDiferencia;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolverDiferenciaRequest(@NotNull ResolucionDiferencia forma, @NotBlank @Size(max = 255) String motivo) {
}
