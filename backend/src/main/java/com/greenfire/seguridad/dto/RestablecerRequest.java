package com.greenfire.seguridad.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RestablecerRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 10, max = 72, message = "La contraseña debe tener entre 10 y 72 caracteres.")
        String password) {
}
