package com.greenfire.ventas.dto;

/** Lo que ve cada perfil de una venta: el dueño, con costos y márgenes; el empleado, sin ellos. */
public sealed interface VentaVista permits VentaResponse, VentaEmpleadoResponse {

    Long id();
}
