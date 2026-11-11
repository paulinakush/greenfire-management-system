package com.greenfire.clientes.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Alta de un cliente. El empleado no manda condiciones: las fija el dueño. */
public record ClienteRequest(@NotNull @Valid ClienteDatosRequest datos, @Valid ClienteCondicionesRequest condiciones) {
}
