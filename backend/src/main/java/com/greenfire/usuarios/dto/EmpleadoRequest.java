package com.greenfire.usuarios.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de un empleado por el dueño de local. El perfil y el ámbito no se eligen: son empleado de su local. */
public record EmpleadoRequest(
        @NotBlank @Size(max = 120) String nombre,
        @UsuarioValido String usuario,
        @NotBlank @Email @Size(max = 255) String email,
        @PasswordValida String password) {
}
