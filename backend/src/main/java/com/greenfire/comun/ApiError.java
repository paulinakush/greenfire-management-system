package com.greenfire.comun;

import java.util.Map;

/** Cuerpo de las respuestas de error de la API. */
public record ApiError(int status, String error, String mensaje, Map<String, String> campos) {
}
