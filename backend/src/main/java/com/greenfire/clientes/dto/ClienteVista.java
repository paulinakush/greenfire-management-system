package com.greenfire.clientes.dto;

/** Lo que devuelve la consulta de clientes: con condiciones comerciales para los dueños, sin ellas para el empleado. */
public sealed interface ClienteVista permits ClienteResponse, ClienteEmpleadoResponse {
}
