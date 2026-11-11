package com.greenfire.comprobantes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Neto, IVA y total de un comprobante a partir de sus renglones, según si el emisor discrimina IVA (punto 6 del
 * plan de la etapa 3). Lo usan la emisión y la vista imprimible, así el desglose impreso es el mismo que se guardó.
 * <ul>
 * <li>Emisor que no discrimina IVA: los precios son finales. Neto = total = suma de subtotales; IVA 0.</li>
 * <li>Emisor que discrimina, precios con IVA: el total es la suma; el IVA de cada alícuota se saca de lo que
 * suman sus renglones (base × a / (100 + a)) y el neto es el resto.</li>
 * <li>Emisor que discrimina, precios sin IVA: el neto es la suma; el IVA de cada alícuota es base × a / 100 y el
 * total, neto más IVA.</li>
 * </ul>
 * Cada importe se redondea a centavos, mitad hacia arriba, como round() de PostgreSQL.
 */
public final class CalculoComprobante {

    private static final BigDecimal CIEN = new BigDecimal("100");

    /** IVA de una alícuota: lo gravado (sin IVA) y el impuesto. */
    public record IvaAlicuota(BigDecimal alicuota, BigDecimal neto, BigDecimal iva) {
    }

    public record Importes(BigDecimal neto, BigDecimal iva, BigDecimal total, List<IvaAlicuota> porAlicuota) {
    }

    /** Un renglón para el cálculo: su subtotal (ya redondeado) y su alícuota. */
    public record Linea(BigDecimal subtotal, BigDecimal alicuota) {
    }

    private CalculoComprobante() {
    }

    public static BigDecimal subtotal(BigDecimal cantidad, BigDecimal precioUnitario) {
        return cantidad.multiply(precioUnitario).setScale(2, RoundingMode.HALF_UP);
    }

    public static Importes calcular(boolean discriminaIva, boolean preciosConIva, List<Linea> lineas) {
        BigDecimal suma = centavos(lineas.stream().map(Linea::subtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (!discriminaIva) {
            return new Importes(suma, centavos(BigDecimal.ZERO), suma, List.of());
        }
        Map<BigDecimal, BigDecimal> bases = new TreeMap<>();
        for (Linea l : lineas) {
            bases.merge(l.alicuota().stripTrailingZeros(), l.subtotal(), BigDecimal::add);
        }
        List<IvaAlicuota> porAlicuota = bases.entrySet().stream().map(e -> {
            BigDecimal a = e.getKey();
            BigDecimal base = e.getValue();
            BigDecimal iva = preciosConIva
                    ? centavos(base.multiply(a).divide(CIEN.add(a), 10, RoundingMode.HALF_UP))
                    : centavos(base.multiply(a).divide(CIEN, 10, RoundingMode.HALF_UP));
            BigDecimal neto = preciosConIva ? base.subtract(iva) : base;
            return new IvaAlicuota(a, centavos(neto), iva);
        }).toList();
        BigDecimal iva = centavos(porAlicuota.stream().map(IvaAlicuota::iva).reduce(BigDecimal.ZERO, BigDecimal::add));
        return preciosConIva
                ? new Importes(suma.subtract(iva), iva, suma, porAlicuota)
                : new Importes(suma, iva, suma.add(iva), porAlicuota);
    }

    public static Importes calcular(Comprobante c) {
        return calcular(c.discriminaIva(), c.isPreciosConIva(), c.getRenglones().stream()
                .map(r -> new Linea(r.getSubtotal(), r.getAlicuotaIva())).toList());
    }

    private static BigDecimal centavos(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }
}
