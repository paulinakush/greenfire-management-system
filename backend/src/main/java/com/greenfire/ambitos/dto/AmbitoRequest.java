package com.greenfire.ambitos.dto;

import com.greenfire.ambitos.TipoAmbito;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta de un ámbito por el administrador técnico. */
public record AmbitoRequest(
        @NotNull TipoAmbito tipo,
        @NotBlank @Size(max = 120) String nombre,
        @NotNull Long entidadFiscalId,
        @Size(max = 255) String direccion,
        @Size(max = 50) String telefono,
        @Email @Size(max = 255) String email,
        @Size(max = 255) String horarios,
        boolean visibleWeb) {
}
