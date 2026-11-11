package com.greenfire.caja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;

/**
 * Reglas de la caja que garantiza la base aunque se escriba por fuera de la aplicación (V13): solo los locales
 * tienen caja, una sola abierta por local, movimientos solo de inserción en una caja abierta, cierre con los
 * totales de sus movimientos y, después, solo la corrección autorizada.
 */
@PruebaIntegracion
class CajaMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;

    Ambito centro;
    long usuario;

    @BeforeEach
    void datos() {
        centro = datos.local("Centro");
        usuario = datos.usuario("Ana").getId();
    }

    // --- Sesiones ---

    @Test
    void soloLosLocalesTienenCajaYUnaSolaAbiertaPorLocal() {
        Ambito fabrica = datos.fabrica();
        rechaza(() -> abrir(fabrica, "0"), "Solo los locales tienen caja");

        long id = abrir(centro, "0");
        rechaza(() -> abrir(centro, "0"), "uq_sesiones_caja_abierta");
        abrir(datos.local("Norte"), "0");
        cerrar(id, "0", "0", "0", "0", "0", "0", "0");
        abrir(centro, "0");
    }

    @Test
    void unaSesionNaceAbiertaYLaAperturaNoCambia() {
        rechaza(() -> jdbc.update("""
                insert into sesiones_caja (ambito_id, usuario_apertura_id, saldo_inicial, estado) values (?, ?, 0, 'cerrada')
                """, centro.getId(), usuario), "nace abierta");
        rechaza(() -> abrir(centro, "-1"), "sesiones_caja_saldo_inicial_check");
        long id = abrir(centro, "10");

        rechaza(() -> jdbc.update("update sesiones_caja set saldo_inicial = 20 where id = ?", id), "no se modifican");
        rechaza(() -> jdbc.update("update sesiones_caja set observaciones = 'x' where id = ?", id), "solo se puede cerrar");
        rechaza(() -> jdbc.update("delete from sesiones_caja where id = ?", id), "no se borran");
        rechaza(() -> jdbc.execute("truncate sesiones_caja cascade"), "TRUNCATE rechazada");
    }

    @Test
    void elCierreLlevaLosTotalesDeSusMovimientosYCuadra() {
        long id = abrir(centro, "100");
        movimiento(id, "venta", null, "ingreso", "efectivo", "500", null);
        movimiento(id, "venta", null, "ingreso", "transferencia", "300", null);
        movimiento(id, "devolucion", null, "egreso", "efectivo", "50", null);
        movimiento(id, "manual", "gasto", "egreso", "efectivo", "20", "Limpieza");
        movimiento(id, "manual", "aporte", "ingreso", "efectivo", "5", "Cambio");
        movimiento(id, "manual", "pago_fabrica", "egreso", "transferencia", "70", "Pago");

        assertThat(jdbc.queryForMap("select * from caja_totales(?)", id))
                .containsEntry("total_efectivo", new java.math.BigDecimal("450.00"))
                .containsEntry("total_transferencia", new java.math.BigDecimal("300.00"))
                .containsEntry("total_billetera", java.math.BigDecimal.ZERO)
                .containsEntry("ingresos_efectivo", new java.math.BigDecimal("5.00"))
                .containsEntry("egresos_efectivo", new java.math.BigDecimal("20.00"));

        // totales que no son los de los movimientos
        rechaza(() -> cerrar(id, "500", "300", "0", "5", "20", "585", "585"), "no son los de sus movimientos");
        // esperado mal calculado
        rechaza(() -> cerrar(id, "450", "300", "0", "5", "20", "600", "600"), "sesiones_caja_cierre_cuadra");
        // sin los totales ni el contado
        rechaza(() -> jdbc.update("""
                update sesiones_caja set estado = 'cerrada', usuario_cierre_id = usuario_apertura_id, cerrada_at = now()
                where id = ?
                """, id), "no son los de sus movimientos");
        // con los totales, pero sin el contado
        rechaza(() -> jdbc.update("""
                update sesiones_caja set estado = 'cerrada', usuario_cierre_id = usuario_apertura_id, cerrada_at = now(),
                    total_efectivo = 450, total_transferencia = 300, total_billetera = 0, total_general = 750,
                    ingresos_efectivo = 5, egresos_efectivo = 20, efectivo_esperado = 535
                where id = ?
                """, id), "sesiones_caja_estado_coherente");
        // esperado = 100 + 450 + 5 - 20 = 535
        cerrar(id, "450", "300", "0", "5", "20", "535", "530");
        assertThat(jdbc.queryForObject("select diferencia from sesiones_caja where id = ?", java.math.BigDecimal.class,
                id)).isEqualByComparingTo("-5");
    }

    @Test
    void despuesDelCierreSoloSeCorrigeElContadoConMotivo() {
        long id = abrir(centro, "10");
        cerrar(id, "0", "0", "0", "0", "0", "10", "8");

        rechaza(() -> jdbc.update("update sesiones_caja set efectivo_contado = 10, diferencia = 0 where id = ?", id),
                "solo se corrige el efectivo contado");
        rechaza(() -> jdbc.update("update sesiones_caja set estado = 'abierta' where id = ?", id),
                "solo se corrige el efectivo contado");
        rechaza(() -> jdbc.update("""
                update sesiones_caja set observaciones = 'otra', corregida_por_usuario_id = ?, corregida_at = now(),
                    motivo_correccion = 'x' where id = ?
                """, usuario, id), "solo cambian el efectivo contado");
        rechaza(() -> corregir(id, "10", "0", " "), "sesiones_caja_correccion_coherente");
        // la diferencia tiene que cuadrar con el contado
        rechaza(() -> corregir(id, "10", "1", "Recuento"), "sesiones_caja_cierre_cuadra");

        corregir(id, "10", "0", "Recuento");
        assertThat(jdbc.queryForObject("select efectivo_contado from sesiones_caja where id = ?",
                java.math.BigDecimal.class, id)).isEqualByComparingTo("10");
        // otra corrección, con otra fecha
        jdbc.update("""
                update sesiones_caja set efectivo_contado = 9, diferencia = -1, corregida_por_usuario_id = ?,
                    corregida_at = corregida_at + interval '1 minute', motivo_correccion = 'Segundo recuento'
                where id = ?
                """, usuario, id);
        rechaza(() -> jdbc.update("delete from sesiones_caja where id = ?", id), "no se borran");
    }

    @Test
    void unaCajaAbiertaNoSeCorrige() {
        long id = abrir(centro, "10");
        rechaza(() -> jdbc.update("""
                update sesiones_caja set corregida_por_usuario_id = ?, corregida_at = now(), motivo_correccion = 'x'
                where id = ?
                """, usuario, id), "solo se puede cerrar");
    }

    // --- Movimientos ---

    @Test
    void losMovimientosSoloSeInsertanEnUnaCajaAbierta() {
        long id = abrir(centro, "0");
        long gasto = movimiento(id, "manual", "gasto", "egreso", "efectivo", "20", "Limpieza");

        rechaza(() -> jdbc.update("update movimientos_caja set monto = 2 where id = ?", gasto), "no se modifican");
        rechaza(() -> jdbc.update("delete from movimientos_caja where id = ?", gasto), "no se modifican");
        rechaza(() -> jdbc.execute("truncate movimientos_caja"), "TRUNCATE rechazada");

        cerrar(id, "0", "0", "0", "0", "20", "-20", "0");
        rechaza(() -> movimiento(id, "manual", "gasto", "egreso", "efectivo", "1", "Tarde"), "está cerrada");
        rechaza(() -> movimiento(id, "venta", null, "ingreso", "efectivo", "1", null), "está cerrada");
    }

    @Test
    void elRubroElTipoElMedioYLaObservacionSeValidan() {
        long id = abrir(centro, "0");
        rechaza(() -> movimiento(id, "manual", "prestamo", "egreso", "efectivo", "1", "x"), "movimientos_caja_rubro_check");
        rechaza(() -> movimiento(id, "manual", null, "egreso", "efectivo", "1", "x"), "movimientos_caja_rubro_segun_origen");
        rechaza(() -> movimiento(id, "venta", "gasto", "ingreso", "efectivo", "1", null), "movimientos_caja_rubro_segun_origen");
        rechaza(() -> movimiento(id, "manual", "gasto", "egreso", "efectivo", "1", " "), "movimientos_caja_observacion_manual");
        rechaza(() -> movimiento(id, "manual", "gasto", "ingreso", "efectivo", "1", "x"), "movimientos_caja_tipo_segun_rubro");
        rechaza(() -> movimiento(id, "manual", "retiro", "ingreso", "efectivo", "1", "x"), "movimientos_caja_tipo_segun_rubro");
        rechaza(() -> movimiento(id, "manual", "aporte", "egreso", "efectivo", "1", "x"), "movimientos_caja_tipo_segun_rubro");
        rechaza(() -> movimiento(id, "venta", null, "egreso", "efectivo", "1", null), "movimientos_caja_tipo_segun_origen");
        rechaza(() -> movimiento(id, "devolucion", null, "ingreso", "efectivo", "1", null), "movimientos_caja_tipo_segun_origen");
        rechaza(() -> movimiento(id, "manual", "gasto", "egreso", "cuenta_corriente", "1", "x"), "movimientos_caja_medio_pago_check");
        rechaza(() -> movimiento(id, "manual", "gasto", "egreso", "efectivo", "0", "x"), "movimientos_caja_monto_check");
        movimiento(id, "manual", "otro", "ingreso", "billetera_virtual", "1", "Sobrante");
        movimiento(id, "manual", "otro", "egreso", "efectivo", "1", "Faltante");
    }

    @Test
    void unErrorSeCorrigeConUnMovimientoContrarioQueNoSuperaAlOriginal() {
        long id = abrir(centro, "0");
        long gasto = movimiento(id, "manual", "gasto", "egreso", "efectivo", "100", "Era 10");
        long venta = movimiento(id, "venta", null, "ingreso", "efectivo", "5", null);

        rechaza(() -> correccion(id, gasto, "gasto", "egreso", "efectivo", "1"), "es un movimiento manual contrario");
        rechaza(() -> correccion(id, gasto, "retiro", "ingreso", "efectivo", "1"), "es un movimiento manual contrario");
        rechaza(() -> correccion(id, gasto, "gasto", "ingreso", "transferencia", "1"), "es un movimiento manual contrario");
        rechaza(() -> correccion(id, venta, "gasto", "egreso", "efectivo", "1"), "es un movimiento manual contrario");
        rechaza(() -> correccion(id, gasto, "gasto", "ingreso", "efectivo", "101"), "superan su monto");

        long primera = correccion(id, gasto, "gasto", "ingreso", "efectivo", "60");
        correccion(id, gasto, "gasto", "ingreso", "efectivo", "30");
        rechaza(() -> correccion(id, gasto, "gasto", "ingreso", "efectivo", "11"), "superan su monto");
        rechaza(() -> correccion(id, primera, "gasto", "egreso", "efectivo", "1"), "es un movimiento manual contrario");

        // de otra caja
        long otra = abrir(datos.local("Norte"), "0");
        rechaza(() -> correccion(otra, gasto, "gasto", "ingreso", "efectivo", "1"), "es un movimiento manual contrario");
        assertThat(jdbc.queryForObject("select egresos_efectivo - ingresos_efectivo from caja_totales(?)",
                java.math.BigDecimal.class, id)).isEqualByComparingTo("10");
    }

    // --- Ayudas ---

    private long abrir(Ambito ambito, String saldo) {
        return jdbc.queryForObject("""
                insert into sesiones_caja (ambito_id, usuario_apertura_id, saldo_inicial) values (?, ?, ?::numeric)
                returning id
                """, Long.class, ambito.getId(), usuario, saldo);
    }

    private void cerrar(long id, String efectivo, String transferencia, String billetera, String ingresos,
            String egresos, String esperado, String contado) {
        jdbc.update("""
                update sesiones_caja set estado = 'cerrada', usuario_cierre_id = ?, cerrada_at = now(),
                    total_efectivo = ?::numeric, total_transferencia = ?::numeric, total_billetera = ?::numeric,
                    total_general = ?::numeric + ?::numeric + ?::numeric,
                    ingresos_efectivo = ?::numeric, egresos_efectivo = ?::numeric,
                    efectivo_esperado = ?::numeric, efectivo_contado = ?::numeric,
                    diferencia = ?::numeric - ?::numeric
                where id = ?
                """, usuario, efectivo, transferencia, billetera, efectivo, transferencia, billetera, ingresos, egresos,
                esperado, contado, contado, esperado, id);
    }

    private void corregir(long id, String contado, String diferencia, String motivo) {
        jdbc.update("""
                update sesiones_caja set efectivo_contado = ?::numeric, diferencia = ?::numeric,
                    corregida_por_usuario_id = ?, corregida_at = now(), motivo_correccion = ?
                where id = ?
                """, contado, diferencia, usuario, motivo, id);
    }

    private long movimiento(long sesion, String origen, String rubro, String tipo, String medio, String monto,
            String observacion) {
        return jdbc.queryForObject("""
                insert into movimientos_caja (sesion_caja_id, usuario_id, origen, rubro, tipo, medio_pago, monto, observacion)
                values (?, ?, ?, ?, ?, ?, ?::numeric, ?) returning id
                """, Long.class, sesion, usuario, origen, rubro, tipo, medio, monto, observacion);
    }

    private long correccion(long sesion, long original, String rubro, String tipo, String medio, String monto) {
        return jdbc.queryForObject("""
                insert into movimientos_caja (sesion_caja_id, usuario_id, origen, rubro, tipo, medio_pago, monto,
                                              observacion, corrige_movimiento_id)
                values (?, ?, 'manual', ?, ?, ?, ?::numeric, 'Corrección', ?) returning id
                """, Long.class, sesion, usuario, rubro, tipo, medio, monto, original);
    }

    /** La base rechaza la operación con ese mensaje o esa restricción. Corre en un savepoint. */
    private void rechaza(ThrowingCallable operacion, String mensaje) {
        jdbc.execute("savepoint intento");
        try {
            assertThatThrownBy(operacion).isInstanceOf(DataAccessException.class).hasStackTraceContaining(mensaje);
        } finally {
            jdbc.execute("rollback to savepoint intento");
        }
    }
}
