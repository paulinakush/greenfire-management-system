package com.greenfire.auditoria;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quita del detalle de auditoría los datos que nunca se guardan: contraseñas, hashes, tokens y datos del
 * certificado de ARCA. Se reemplaza el valor (también el anterior y el nuevo de una edición) por
 * {@link #OCULTO}, así queda registrado que el campo cambió pero no su contenido.
 */
final class Redactor {

    static final String OCULTO = "[oculto]";
    private static final List<String> SENSIBLES =
            List.of("password", "contrasena", "hash", "token", "certificado", "secret");

    private Redactor() {
    }

    static Object limpiar(Object valor) {
        if (valor instanceof Map<?, ?> mapa) {
            Map<String, Object> limpio = new LinkedHashMap<>();
            mapa.forEach((clave, v) -> limpio.put(String.valueOf(clave),
                    esSensible(String.valueOf(clave)) ? ocultar(v) : limpiar(v)));
            return limpio;
        }
        if (valor instanceof Collection<?> lista) {
            return lista.stream().map(Redactor::limpiar).toList();
        }
        return valor;
    }

    static boolean esSensible(String clave) {
        String normalizada = clave.toLowerCase().replaceAll("[^a-z]", "");
        return SENSIBLES.stream().anyMatch(normalizada::contains);
    }

    /** En una edición ({antes, despues}) oculta ambos valores; en cualquier otro caso, el valor entero. */
    private static Object ocultar(Object valor) {
        if (valor instanceof Map<?, ?> mapa) {
            Map<String, Object> oculto = new LinkedHashMap<>();
            mapa.forEach((clave, v) -> oculto.put(String.valueOf(clave), v == null ? null : OCULTO));
            return oculto;
        }
        return valor == null ? null : OCULTO;
    }
}
