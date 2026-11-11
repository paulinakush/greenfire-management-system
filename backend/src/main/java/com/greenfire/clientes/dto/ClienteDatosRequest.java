package com.greenfire.clientes.dto;

import com.greenfire.clientes.TipoDocumento;
import com.greenfire.fiscal.CondicionIva;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Datos de identificación y contacto, que también carga el empleado. Nombre o razón social, al menos uno.
 * El documento va sin puntos ni guiones; la condición frente al IVA es consumidor final si no se indica.
 */
public record ClienteDatosRequest(
        @Size(max = 200) String nombre,
        @Size(max = 200) String razonSocial,
        TipoDocumento documentoTipo,
        @Pattern(regexp = "\\d{7,11}", message = "El documento va solo con números, sin puntos ni guiones.")
        String documentoNumero,
        CondicionIva condicionIva,
        @Size(max = 50) String telefono,
        @Email @Size(max = 255) String email,
        @Size(max = 255) String direccion) {
}
