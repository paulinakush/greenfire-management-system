package com.greenfire.caja;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Totales de una sesión de caja a partir de sus movimientos. Es el mismo cálculo que hace la base al verificar el
 * cierre (función caja_totales, V13).
 * <ul>
 * <li>Por medio de pago: los cobros netos (ventas + cobranzas - devoluciones). Los de transferencia y billetera
 * virtual se totalizan aparte y no suman al efectivo.</li>
 * <li>Efectivo esperado = saldo inicial + ventas en efectivo + cobranzas en efectivo + ingresos manuales en
 * efectivo - egresos manuales en efectivo - devoluciones en efectivo.</li>
 * </ul>
 */
public final class CierreCaja {

    public record Totales(
            BigDecimal ventasEfectivo,
            BigDecimal cobranzasEfectivo,
            BigDecimal devolucionesEfectivo,
            BigDecimal totalEfectivo,
            BigDecimal totalTransferencia,
            BigDecimal totalBilletera,
            BigDecimal totalGeneral,
            BigDecimal ingresosEfectivo,
            BigDecimal egresosEfectivo,
            BigDecimal efectivoEsperado) {
    }

    private CierreCaja() {
    }

    public static Totales calcular(BigDecimal saldoInicial, List<MovimientoCaja> movimientos) {
        BigDecimal ventas = suma(movimientos, OrigenMovimientoCaja.venta, MedioPago.efectivo, null);
        BigDecimal cobranzas = suma(movimientos, OrigenMovimientoCaja.cobranza, MedioPago.efectivo, null);
        BigDecimal devoluciones = suma(movimientos, OrigenMovimientoCaja.devolucion, MedioPago.efectivo, null);
        BigDecimal efectivo = ventas.add(cobranzas).subtract(devoluciones);
        BigDecimal transferencia = cobros(movimientos, MedioPago.transferencia);
        BigDecimal billetera = cobros(movimientos, MedioPago.billetera_virtual);
        BigDecimal ingresos = suma(movimientos, OrigenMovimientoCaja.manual, MedioPago.efectivo,
                TipoMovimientoCaja.ingreso);
        BigDecimal egresos = suma(movimientos, OrigenMovimientoCaja.manual, MedioPago.efectivo,
                TipoMovimientoCaja.egreso);
        return new Totales(ventas, cobranzas, devoluciones, efectivo, transferencia, billetera,
                efectivo.add(transferencia).add(billetera), ingresos, egresos,
                centavos(saldoInicial).add(efectivo).add(ingresos).subtract(egresos));
    }

    /** Cobros netos de un medio: ventas y cobranzas suman, devoluciones restan. */
    private static BigDecimal cobros(List<MovimientoCaja> movimientos, MedioPago medio) {
        return centavos(movimientos.stream()
                .filter(m -> m.getOrigen() != OrigenMovimientoCaja.manual && m.getMedioPago() == medio)
                .map(MovimientoCaja::montoConSigno).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal suma(List<MovimientoCaja> movimientos, OrigenMovimientoCaja origen, MedioPago medio,
            TipoMovimientoCaja tipo) {
        return centavos(movimientos.stream()
                .filter(m -> m.getOrigen() == origen && m.getMedioPago() == medio
                        && (tipo == null || m.getTipo() == tipo))
                .map(MovimientoCaja::getMonto).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal centavos(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }
}
