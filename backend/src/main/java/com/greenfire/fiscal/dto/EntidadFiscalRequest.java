package com.greenfire.fiscal.dto;

import java.time.LocalDate;

import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.Cuit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EntidadFiscalRequest(
        @NotBlank @Size(max = 200) String razonSocial,
        @NotBlank @Cuit String cuit,
        @NotNull CondicionIva condicionIva,
        @Size(max = 255) String domicilioFiscal,
        @Size(max = 255) String arcaCertificadoRef,
        LocalDate arcaCertificadoVence) {
}
