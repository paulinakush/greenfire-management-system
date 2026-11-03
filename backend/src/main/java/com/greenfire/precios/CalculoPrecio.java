package com.greenfire.precios;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cuentas de precios según la condición fiscal de la entidad del ámbito dueño de la lista.
 * <ul>
 * <li>Si la entidad discrimina IVA (responsable inscripto), el margen se calcula sobre el precio neto: si la lista
 * tiene precios con IVA, se divide por (1 + alícuota).</li>
 * <li>Si no lo discrimina, el precio de la lista es el precio final y el margen se calcula sobre él, sin sacar
 * IVA.</li>
 * </ul>
 * Margen = (precio neto − costo) / precio neto, con el costo del ámbito de la lista.
 */
public final class CalculoPrecio {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final int ESCALA_INTERMEDIA = 10;

    private CalculoPrecio() {
    }

    /** Precio sin IVA si la entidad discrimina IVA; si no, el precio de la lista tal cual. */
    public static BigDecimal precioNeto(BigDecimal precio, boolean discriminaIva, boolean preciosConIva,
            BigDecimal alicuotaIva) {
        return netoSinRedondear(precio, discriminaIva, preciosConIva, alicuotaIva).setScale(2, RoundingMode.HALF_UP);
    }

    /** Lo que paga el consumidor: suma el IVA solo si la entidad lo discrimina y la lista es sin IVA. */
    public static BigDecimal precioFinal(BigDecimal precio, boolean discriminaIva, boolean preciosConIva,
            BigDecimal alicuotaIva) {
        if (!discriminaIva || preciosConIva) {
            return precio;
        }
        return precio.multiply(factorIva(alicuotaIva)).setScale(2, RoundingMode.HALF_UP);
    }

    /** Fracción con cuatro decimales (0.2500 es 25 %), o null si no hay costo. */
    public static BigDecimal margen(BigDecimal precio, BigDecimal costo, boolean discriminaIva, boolean preciosConIva,
            BigDecimal alicuotaIva) {
        if (costo == null) {
            return null;
        }
        BigDecimal neto = netoSinRedondear(precio, discriminaIva, preciosConIva, alicuotaIva);
        return neto.subtract(costo).divide(neto, 4, RoundingMode.HALF_UP);
    }

    /** Al múltiplo de {@code multiplo} más cercano (la mitad redondea hacia arriba), con dos decimales. */
    public static BigDecimal redondear(BigDecimal valor, BigDecimal multiplo) {
        return valor.divide(multiplo, 0, RoundingMode.HALF_UP).multiply(multiplo).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal netoSinRedondear(BigDecimal precio, boolean discriminaIva, boolean preciosConIva,
            BigDecimal alicuotaIva) {
        if (!discriminaIva || !preciosConIva) {
            return precio;
        }
        return precio.divide(factorIva(alicuotaIva), ESCALA_INTERMEDIA, RoundingMode.HALF_UP);
    }

    private static BigDecimal factorIva(BigDecimal alicuotaIva) {
        return BigDecimal.ONE.add(alicuotaIva.divide(CIEN));
    }
}
