package com.greenfire.ventas.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.caja.MedioPago;
import com.greenfire.ventas.EstadoVenta;
import com.greenfire.ventas.dto.VentaResponse.Anulacion;
import com.greenfire.ventas.dto.VentaResponse.ClienteDeVenta;
import com.greenfire.ventas.dto.VentaResponse.Devolucion;

/** Una venta para el empleado de local: sin costos ni márgenes. */
public record VentaEmpleadoResponse(
        Long id,
        OffsetDateTime fechaHora,
        String usuario,
        ClienteDeVenta cliente,
        MedioPago medioPago,
        BigDecimal subtotal,
        BigDecimal porcentajeDescuento,
        BigDecimal descuento,
        BigDecimal total,
        BigDecimal totalDevuelto,
        EstadoVenta estado,
        ComprobanteDeVenta comprobante,
        Anulacion anulacion,
        List<Renglon> renglones,
        List<Devolucion> devoluciones) implements VentaVista {

    public record Renglon(Long id, Long articuloId, String articulo, String ubicacion, BigDecimal cantidad,
            BigDecimal cantidadDevuelta, BigDecimal precioLista, BigDecimal precioUnitario, BigDecimal subtotal) {
    }
}
