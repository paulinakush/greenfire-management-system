package com.greenfire.entregas.dto;

/** Lo que ve cada perfil de una entrega: la fábrica, el dueño del local o el empleado. */
public sealed interface EntregaVista permits EntregaResponse, EntregaLocalResponse, RecepcionEmpleadoResponse {
}
