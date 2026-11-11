package com.greenfire.caja.dto;

/** Lo que ve cada perfil de una sesión: el dueño todo; el empleado, a ciegas hasta que se cierra. */
public sealed interface SesionCajaVista permits SesionCajaResponse, SesionCajaEmpleadoResponse {
}
