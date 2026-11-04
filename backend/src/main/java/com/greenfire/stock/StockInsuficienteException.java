package com.greenfire.stock;

import com.greenfire.comun.ReglaNegocioException;

/** Un movimiento dejaría el stock negativo. Responde 409 y revierte toda la operación. */
public class StockInsuficienteException extends ReglaNegocioException {

    public static final String MENSAJE = "Stock insuficiente";

    public StockInsuficienteException(String detalle) {
        super(MENSAJE + ": " + detalle);
    }
}
