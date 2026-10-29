package com.greenfire.ambitos.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Edición de un ámbito por el administrador técnico. El tipo y la entidad fiscal no se cambian. */
public record AmbitoEdicionAdminRequest(
        @NotBlank @Size(max = 120) String nombre,
        @Size(max = 255) String direccion,
        @Size(max = 50) String telefono,
        @Email @Size(max = 255) String email,
        @Size(max = 255) String horarios,
        boolean visibleWeb) {
}
