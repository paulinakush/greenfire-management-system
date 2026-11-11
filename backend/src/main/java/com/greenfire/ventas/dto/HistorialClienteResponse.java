package com.greenfire.ventas.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.caja.MedioPago;
import com.greenfire.ventas.EstadoVenta;

/** Las ventas de un cliente, de la más nueva a la más vieja, sin costos. Los pedidos se suman con el módulo 9. */
public record HistorialClienteResponse(Long clienteId, List<VentaDelCliente> ventas, int pagina, int tamano,
        long total) {

    public record VentaDelCliente(Long id, OffsetDateTime fechaHora, ComprobanteDeVenta comprobante,
            MedioPago medioPago, BigDecimal total, BigDecimal totalDevuelto, EstadoVenta estado) {
    }
}
