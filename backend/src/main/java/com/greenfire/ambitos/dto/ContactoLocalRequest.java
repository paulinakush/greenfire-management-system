package com.greenfire.ambitos.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Lo único que el dueño de local puede editar de su local. */
public record ContactoLocalRequest(
        @Size(max = 255) String direccion,
        @Size(max = 255) String horarios,
        @Size(max = 50) String telefono,
        @Email @Size(max = 255) String email) {
}
