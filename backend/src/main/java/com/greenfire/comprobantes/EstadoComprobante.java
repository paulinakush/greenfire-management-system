package com.greenfire.comprobantes;

/** Un comprobante emitido solo puede pasar a anulado, una vez (V12). */
public enum EstadoComprobante {
    emitido,
    anulado
}
