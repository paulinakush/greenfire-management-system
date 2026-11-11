package com.greenfire.ventas;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.greenfire.comprobantes.CalculoComprobante;
import com.greenfire.precios.CalculoPrecio;

/**
 * Precio, descuento y subtotal de cada renglón de una venta.
 * <ul>
 * <li>Precio de lista: el precio final de la lista. Si la entidad discrimina IVA y la lista es sin IVA, se suma el
 * IVA del artículo ({@link CalculoPrecio#precioFinal}).</li>
 * <li>Precio unitario: el de lista con el porcentaje de descuento de la venta, redondeado a centavos.</li>
 * <li>Subtotal: cantidad por precio unitario, redondeado a centavos, con el mismo cálculo que el comprobante
 * ({@link CalculoComprobante#subtotal}). Así el total de la venta (la suma de los subtotales) es el del
 * comprobante, que se emite con los mismos precios finales.</li>
 * </ul>
 */
public final class CalculoVenta {

    private static final BigDecimal CIEN = new BigDecimal("100");

    /** Precios y subtotales de un renglón: a precio de lista y con el descuento. */
    public record Linea(BigDecimal precioLista, BigDecimal precioUnitario, BigDecimal subtotalLista,
            BigDecimal subtotal) {
    }

    private CalculoVenta() {
    }

    public static Linea linea(BigDecimal cantidad, BigDecimal precioDeLaLista, boolean preciosConIva,
            boolean discriminaIva, BigDecimal alicuotaIva, BigDecimal porcentajeDescuento) {
        BigDecimal lista = CalculoPrecio.precioFinal(precioDeLaLista, discriminaIva, preciosConIva, alicuotaIva)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal unitario = conDescuento(lista, porcentajeDescuento);
        return new Linea(lista, unitario, CalculoComprobante.subtotal(cantidad, lista),
                CalculoComprobante.subtotal(cantidad, unitario));
    }

    /** El precio con el porcentaje de descuento, redondeado a centavos (la mitad hacia arriba). */
    public static BigDecimal conDescuento(BigDecimal precio, BigDecimal porcentaje) {
        return precio.multiply(CIEN.subtract(porcentaje)).divide(CIEN, 2, RoundingMode.HALF_UP);
    }

    /** (neto − costo) / neto, con cuatro decimales; null sin costo o sin neto. */
    public static BigDecimal margen(BigDecimal neto, BigDecimal costo) {
        if (costo == null || neto == null || neto.signum() <= 0) {
            return null;
        }
        return neto.subtract(costo).divide(neto, 4, RoundingMode.HALF_UP);
    }

    /** El precio sin IVA si el emisor lo discrimina (los precios de la venta son finales); si no, el mismo. */
    public static BigDecimal neto(BigDecimal precioFinal, boolean discriminaIva, BigDecimal alicuotaIva) {
        return CalculoPrecio.precioNeto(precioFinal, discriminaIva, true, alicuotaIva);
    }
}
