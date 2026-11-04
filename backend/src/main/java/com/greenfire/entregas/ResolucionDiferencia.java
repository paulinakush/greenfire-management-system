package com.greenfire.entregas;

/** Cómo resuelve la fábrica el faltante de una entrega. */
public enum ResolucionDiferencia {
    /** El faltante vuelve al stock de la fábrica, en la ubicación de origen (movimiento de devolución). */
    reingreso,
    /** El faltante se da por perdido: no genera movimiento. */
    perdida
}
