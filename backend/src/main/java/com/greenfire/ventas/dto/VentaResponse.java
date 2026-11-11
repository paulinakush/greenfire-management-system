package com.greenfire.ventas.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.caja.MedioPago;
import com.greenfire.ventas.EstadoVenta;

/** Una venta para los dueños, con el costo y el margen de cada renglón y del total. */
public record VentaResponse(
        Long id,
        Long ambitoId,
        OffsetDateTime fechaHora,
        String usuario,
        ClienteDeVenta cliente,
        MedioPago medioPago,
        LocalDate fechaCobro,
        Long sesionCajaId,
        BigDecimal subtotal,
        BigDecimal porcentajeDescuento,
        BigDecimal descuento,
        BigDecimal total,
        BigDecimal totalDevuelto,
        EstadoVenta estado,
        ComprobanteDeVenta comprobante,
        Anulacion anulacion,
        BigDecimal costoTotal,
        BigDecimal margen,
        List<Renglon> renglones,
        List<Devolucion> devoluciones) implements VentaVista {

    public record ClienteDeVenta(Long id, String nombre) {
    }

    public record Anulacion(String usuario, OffsetDateTime fecha, String motivo) {
    }

    public record Renglon(Long id, Long articuloId, String articulo, Long ubicacionId, String ubicacion,
            BigDecimal cantidad, BigDecimal cantidadDevuelta, BigDecimal precioLista, BigDecimal precioUnitario,
            BigDecimal alicuotaIva, BigDecimal subtotal, BigDecimal costoUnitario, BigDecimal margen) {
    }

    public record Devolucion(Long id, OffsetDateTime fechaHora, String usuario, String motivo, BigDecimal total,
            ComprobanteDeVenta notaCredito, List<RenglonDevuelto> renglones) {
    }

    public record RenglonDevuelto(Long detalleVentaId, String articulo, BigDecimal cantidad, BigDecimal subtotal) {
    }
}
