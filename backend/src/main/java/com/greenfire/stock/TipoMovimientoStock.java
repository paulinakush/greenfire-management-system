package com.greenfire.stock;

/** En la etapa 2 se usan ajuste, conteo, entrega_salida, entrega_recepcion, anulacion y devolucion. */
public enum TipoMovimientoStock {
    venta,
    entrega_pedido,
    compra,
    entrega_salida,
    entrega_recepcion,
    produccion_consumo,
    produccion_ingreso,
    ajuste,
    conteo,
    anulacion,
    devolucion
}
