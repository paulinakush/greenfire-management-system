package com.greenfire.catalogo.dto;

/** Lo que devuelve la consulta de artículos: con costo para los dueños, sin costo para el empleado. */
public sealed interface ArticuloVista permits ArticuloResponse, ArticuloEmpleadoResponse {
}
