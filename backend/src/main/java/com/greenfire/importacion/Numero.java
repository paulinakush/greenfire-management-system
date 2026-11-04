package com.greenfire.importacion;

import java.math.BigDecimal;

/** Números de una planilla: con coma o con punto decimal, sin separador de miles. */
final class Numero {

    private Numero() {
    }

    /** El número, o una {@link IllegalArgumentException} con el motivo para informar en la fila. */
    static BigDecimal leer(String texto, String campo) {
        String limpio = texto.replace(" ", "").replace(" ", "");
        if (limpio.contains(",") && limpio.contains(".")) {
            throw new IllegalArgumentException("El " + campo + " '" + texto
                    + "' no es válido: usá coma o punto para los decimales, sin separador de miles.");
        }
        try {
            return new BigDecimal(limpio.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El " + campo + " '" + texto + "' no es un número.");
        }
    }
}
