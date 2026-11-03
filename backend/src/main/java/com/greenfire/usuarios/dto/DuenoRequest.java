package com.greenfire.usuarios.dto;

import com.greenfire.seguridad.Perfil;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta de un dueño (de fábrica o de local) por el administrador técnico. */
public record DuenoRequest(
        @NotBlank @Size(max = 120) String nombre,
        @NotBlank @Email @Size(max = 255) String email,
        @PasswordValida String password,
        @NotNull Long ambitoId,
        @NotNull Perfil perfil) {
}
