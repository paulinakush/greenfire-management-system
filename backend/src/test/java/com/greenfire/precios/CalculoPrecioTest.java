package com.greenfire.precios;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** IVA y margen según la condición fiscal de la entidad dueña de la lista. */
class CalculoPrecioTest {

    static final BigDecimal IVA_21 = new BigDecimal("21");
    static final BigDecimal PRECIO = new BigDecimal("1210.00");
    static final BigDecimal COSTO = new BigDecimal("600.00");

    @Test
    void unResponsableInscriptoConPreciosConIvaCalculaElMargenSobreElNeto() {
        assertThat(CalculoPrecio.precioNeto(PRECIO, true, true, IVA_21)).isEqualByComparingTo("1000.00");
        assertThat(CalculoPrecio.margen(PRECIO, COSTO, true, true, IVA_21)).isEqualByComparingTo("0.4000");
    }

    @Test
    void unResponsableInscriptoConPreciosSinIvaUsaElPrecioComoNeto() {
        assertThat(CalculoPrecio.precioNeto(PRECIO, true, false, IVA_21)).isEqualByComparingTo("1210.00");
        assertThat(CalculoPrecio.margen(PRECIO, COSTO, true, false, IVA_21)).isEqualByComparingTo("0.5041");
    }

    @Test
    void siLaEntidadNoDiscriminaIvaElPrecioEsFinalYNoSeLeSacaIva() {
        assertThat(CalculoPrecio.precioNeto(PRECIO, false, true, IVA_21)).isEqualByComparingTo("1210.00");
        assertThat(CalculoPrecio.margen(PRECIO, COSTO, false, true, IVA_21)).isEqualByComparingTo("0.5041");
    }

    @Test
    void laAlicuotaDeDiezYMedioTambienSeDescuenta() {
        assertThat(CalculoPrecio.precioNeto(new BigDecimal("1105.00"), true, true, new BigDecimal("10.5")))
                .isEqualByComparingTo("1000.00");
    }

    @Test
    void sinCostoNoHayMargen() {
        assertThat(CalculoPrecio.margen(PRECIO, null, true, true, IVA_21)).isNull();
    }

    @Test
    void elPrecioFinalSumaElIvaSoloSiLaEntidadLoDiscriminaYLaListaEsSinIva() {
        assertThat(CalculoPrecio.precioFinal(new BigDecimal("1000.00"), true, false, IVA_21))
                .isEqualByComparingTo("1210.00");
        assertThat(CalculoPrecio.precioFinal(PRECIO, true, true, IVA_21)).isEqualByComparingTo("1210.00");
        assertThat(CalculoPrecio.precioFinal(PRECIO, false, true, IVA_21)).isEqualByComparingTo("1210.00");
    }

    @Test
    void redondeaAlMultiploMasCercano() {
        assertThat(CalculoPrecio.redondear(new BigDecimal("1234.567"), new BigDecimal("0.01")))
                .isEqualByComparingTo("1234.57");
        assertThat(CalculoPrecio.redondear(new BigDecimal("1234.567"), BigDecimal.TEN)).isEqualByComparingTo("1230");
        assertThat(CalculoPrecio.redondear(new BigDecimal("1225"), new BigDecimal("50"))).isEqualByComparingTo("1250");
    }
}
