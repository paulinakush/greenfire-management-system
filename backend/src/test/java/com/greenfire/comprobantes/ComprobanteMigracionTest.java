package com.greenfire.comprobantes;

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
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.PuntoVenta;

/**
 * Reglas de los comprobantes y su numeración que garantiza la base, aunque se escriba por fuera de la aplicación
 * (V12). Las verificaciones diferidas se corren con {@code set constraints all immediate}, porque la transacción
 * del test no se confirma. Las que necesitan un comprobante ya confirmado están en {@link ComprobanteConcurrenciaTest}.
 */
@PruebaIntegracion
class ComprobanteMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;

    Ambito centro;
    Ambito norte;
    PuntoVenta pv;
    PuntoVenta pvNorte;
    long usuario;

    @BeforeEach
    void datos() {
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        pv = datos.puntoVentaX(centro);
        pvNorte = datos.puntoVentaX(norte);
        usuario = datos.usuario("Ana").getId();
    }

    // --- Numeración ---

    @Test
    void elNumeroSoloAvanzaDeAUnoYSoloSiElAnteriorTieneComprobante() {
        rechaza(() -> fijarUltimo(pv, "comprobante_venta", 2), "avanza de a uno");

        assertThat(tomar(pv, "comprobante_venta")).isEqualTo(1);
        // el 1 todavía no tiene comprobante: no se puede tomar el 2
        rechaza(() -> tomar(pv, "comprobante_venta"), "todavía no tiene comprobante");
        emitirConNumero(pv, "comprobante_venta", 1);
        assertThat(tomar(pv, "comprobante_venta")).isEqualTo(2);
        emitirConNumero(pv, "comprobante_venta", 2);

        rechaza(() -> fijarUltimo(pv, "comprobante_venta", 1), "avanza de a uno");
        rechaza(() -> jdbc.update("""
                update punto_venta_comprobantes set tipo = 'recibo'
                where punto_venta_id = ? and tipo = 'comprobante_venta'
                """, pv.getId()), "no cambian");
        confirmarRestricciones();
    }

    @Test
    void unNumeroTomadoSinComprobanteNoSeConfirma() {
        tomar(pv, "recibo");

        rechaza(this::confirmarRestricciones, "quedó sin comprobante");
    }

    @Test
    void unComprobanteSoloSeInsertaConElNumeroTomado() {
        rechaza(() -> emitirConNumero(pv, "comprobante_venta", 1), "no es el último tomado");
        tomar(pv, "comprobante_venta");
        rechaza(() -> emitirConNumero(pv, "comprobante_venta", 5), "no es el último tomado");
        // con el número de otro tipo tampoco
        rechaza(() -> emitirConNumero(pv, "recibo", 1), "no es el último tomado");
        emitirConNumero(pv, "comprobante_venta", 1);
        // ni repetido
        rechaza(() -> emitirConNumero(pv, "comprobante_venta", 1), "uq_comprobantes_numero");
    }

    @Test
    void alVolverAAgregarUnTipoLaNumeracionSigueDesdeElUltimoEmitido() {
        tomar(pv, "remito");
        emitirConNumero(pv, "remito", 1);
        tomar(pv, "remito");
        emitirConNumero(pv, "remito", 2);

        jdbc.update("delete from punto_venta_comprobantes where punto_venta_id = ? and tipo = 'remito'", pv.getId());
        // aunque se intente insertar con otro número, la base lo fija en el último emitido
        jdbc.update("""
                insert into punto_venta_comprobantes (punto_venta_id, tipo, letra, ultimo_numero)
                values (?, 'remito', 'X', 0)
                """, pv.getId());

        assertThat(jdbc.queryForObject("""
                select ultimo_numero from punto_venta_comprobantes where punto_venta_id = ? and tipo = 'remito'
                """, Integer.class, pv.getId())).isEqualTo(2);
        assertThat(tomar(pv, "remito")).isEqualTo(3);
    }

    @Test
    void elComprobanteDeVentaEsSoloXYLaFacturaNuncaEsX() {
        rechaza(() -> jdbc.update("""
                insert into punto_venta_comprobantes (punto_venta_id, tipo, letra) values (?, 'comprobante_venta', 'C')
                """, pvNorte.getId()), "punto_venta_comprobantes_letra_segun_tipo");
        rechaza(() -> jdbc.update("""
                insert into punto_venta_comprobantes (punto_venta_id, tipo, letra) values (?, 'factura', 'X')
                """, pvNorte.getId()), "punto_venta_comprobantes_letra_segun_tipo");
        jdbc.update("insert into punto_venta_comprobantes (punto_venta_id, tipo, letra) values (?, 'nota_debito', 'C')",
                pvNorte.getId());
    }

    // --- Emisión ---

    @Test
    void elEmisorEsLaEntidadDelPuntoDeVentaYElClienteEsDeSuAmbito() {
        long ajeno = datos.cliente(norte, "Ajeno").getId();
        tomar(pv, "comprobante_venta");

        rechaza(() -> insertar(pv, "comprobante_venta", 1, "Otra razón social", "monotributo", null, 0),
                "no son los de la entidad fiscal");
        rechaza(() -> insertar(pv, "comprobante_venta", 1, centro.getEntidadFiscal().getRazonSocial(),
                "responsable_inscripto", null, 0), "no son los de la entidad fiscal");
        rechaza(() -> insertar(pv, "comprobante_venta", 1, centro.getEntidadFiscal().getRazonSocial(), "monotributo",
                ajeno, 0), "no es del ámbito del punto de venta");
    }

    @Test
    void unPuntoDeVentaDesactivadoNoEmite() {
        tomar(pv, "comprobante_venta");
        jdbc.update("update puntos_venta set activo = false where id = ?", pv.getId());

        rechaza(() -> emitirConNumero(pv, "comprobante_venta", 1), "está desactivado");
    }

    @Test
    void soloUnEmisorQueDiscriminaIvaTieneIva() {
        // Centro es monotributista: sin IVA y con precios finales (el número tomado se revierte con el intento)
        rechaza(() -> {
            tomar(pv, "comprobante_venta");
            insertar(pv, "comprobante_venta", 1, centro.getEntidadFiscal().getRazonSocial(), "monotributo", null, 21);
        }, "comprobantes_iva_segun_emisor");

        Ambito ruta = datos.ambito(TipoAmbito.local, "Ruta", CondicionIva.responsable_inscripto);
        PuntoVenta pvRuta = datos.puntoVentaX(ruta);
        tomar(pvRuta, "comprobante_venta");
        long id = insertar(pvRuta, "comprobante_venta", 1, ruta.getEntidadFiscal().getRazonSocial(),
                "responsable_inscripto", null, 21);
        renglon(id, "100", "100.00"); // precios sin IVA: los renglones suman el neto
        confirmarRestricciones();
    }

    @Test
    void cadaComprobanteTieneRenglonesQueSumanSuTotal() {
        tomar(pv, "recibo");
        emitirSinRenglones(pv, "recibo", 1);
        rechaza(this::confirmarRestricciones, "no tiene renglones");
    }

    @Test
    void elDetalleSeCargaAunqueLaEmisionCorraEnUnSavepoint() {
        jdbc.execute("savepoint emision");
        long id = emitirConNumero(pv, "presupuesto", tomar(pv, "presupuesto"));
        jdbc.execute("release savepoint emision");
        renglon(id, "0", "0.00"); // sigue en la misma transacción
        confirmarRestricciones();

        // si el savepoint se revierte, la emisión y su número se van con él
        jdbc.execute("savepoint emision");
        emitirConNumero(pv, "presupuesto", tomar(pv, "presupuesto"));
        jdbc.execute("rollback to savepoint emision");
        assertThat(tomar(pv, "presupuesto")).isEqualTo(2);
    }

    @Test
    void elSubtotalEsCantidadPorPrecio() {
        tomar(pv, "recibo");
        long id = emitirSinRenglones(pv, "recibo", 1);

        rechaza(() -> jdbc.update("""
                insert into detalle_comprobante (comprobante_id, descripcion, cantidad, precio_unitario, alicuota_iva,
                                                 subtotal)
                values (?, 'Pago', 3, 33.33, 0, 100)
                """, id), "detalle_comprobante_subtotal");
    }

    // --- Inmutabilidad ---

    @Test
    void unComprobanteNoSeModificaNiSeBorraSoloSeAnulaUnaVez() {
        long id = emitirConNumero(pv, "comprobante_venta", tomar(pv, "comprobante_venta"));

        rechaza(() -> jdbc.update("update comprobantes set total = 999, neto = 999 where id = ?", id), "solo se anula");
        rechaza(() -> jdbc.update("update comprobantes set receptor_razon_social = 'Otro' where id = ?", id),
                "solo se anula");
        rechaza(() -> jdbc.update("delete from comprobantes where id = ?", id), "no se borran");
        confirmarRestricciones(); // TRUNCATE no corre con verificaciones diferidas pendientes
        rechaza(() -> jdbc.execute("truncate comprobantes cascade"), "no se borran");
        // anular cambiando otro dato
        rechaza(() -> jdbc.update("""
                update comprobantes set estado = 'anulado', anulado_por_usuario_id = ?, anulado_at = now(),
                                        motivo_anulacion = 'Error', observaciones = 'x'
                where id = ?
                """, usuario, id), "no cambia nada más");
        // anular sin motivo
        rechaza(() -> jdbc.update("""
                update comprobantes set estado = 'anulado', anulado_por_usuario_id = ?, anulado_at = now(),
                                        motivo_anulacion = ' '
                where id = ?
                """, usuario, id), "comprobantes_anulacion_coherente");

        jdbc.update("""
                update comprobantes set estado = 'anulado', anulado_por_usuario_id = ?, anulado_at = now(),
                                        motivo_anulacion = 'Error de carga'
                where id = ?
                """, usuario, id);

        rechaza(() -> jdbc.update("update comprobantes set motivo_anulacion = 'Otro' where id = ?", id),
                "está anulado");
        rechaza(() -> jdbc.update("""
                update comprobantes set estado = 'emitido', anulado_por_usuario_id = null, anulado_at = null,
                                        motivo_anulacion = null
                where id = ?
                """, id), "está anulado");
        rechaza(() -> jdbc.update("delete from detalle_comprobante where comprobante_id = ?", id),
                "no se modifica ni se borra");
        rechaza(() -> jdbc.execute("truncate detalle_comprobante"), "no se modifica ni se borra");
    }

    @Test
    void unComprobanteNaceEmitidoYSoloLasNotasLlevanAsociado() {
        long venta = emitirConNumero(pv, "comprobante_venta", tomar(pv, "comprobante_venta"));
        tomar(pv, "recibo");

        rechaza(() -> jdbc.update("""
                insert into comprobantes (tipo, letra, punto_venta_id, numero, emisor_razon_social, emisor_cuit,
                    emisor_condicion_iva, emisor_nombre, usuario_id, precios_con_iva, neto, iva, total,
                    comprobante_asociado_id)
                select 'recibo', 'X', ?, 1, e.razon_social, e.cuit, e.condicion_iva, 'Centro', ?, true, 10, 0, 10, ?
                from entidades_fiscales e where e.id = ?
                """, pv.getId(), usuario, venta, centro.getEntidadFiscal().getId()),
                "comprobantes_asociado_segun_tipo");
        rechaza(() -> jdbc.update("""
                insert into comprobantes (tipo, letra, punto_venta_id, numero, emisor_razon_social, emisor_cuit,
                    emisor_condicion_iva, emisor_nombre, usuario_id, precios_con_iva, neto, iva, total, estado,
                    anulado_por_usuario_id, anulado_at, motivo_anulacion)
                select 'recibo', 'X', ?, 1, e.razon_social, e.cuit, e.condicion_iva, 'Centro', ?, true, 10, 0, 10,
                    'anulado', ?, now(), 'x'
                from entidades_fiscales e where e.id = ?
                """, pv.getId(), usuario, usuario, centro.getEntidadFiscal().getId()), "nace emitido");
    }

    @Test
    void unaNotaNoSeAsociaAUnComprobanteDeOtraEntidad() {
        long ventaNorte = emitirConNumero(pvNorte, "comprobante_venta", tomar(pvNorte, "comprobante_venta"));
        tomar(pv, "nota_credito");

        rechaza(() -> jdbc.update("""
                insert into comprobantes (tipo, letra, punto_venta_id, numero, emisor_razon_social, emisor_cuit,
                    emisor_condicion_iva, emisor_nombre, usuario_id, precios_con_iva, neto, iva, total,
                    comprobante_asociado_id)
                select 'nota_credito', 'X', ?, 1, e.razon_social, e.cuit, e.condicion_iva, 'Centro', ?, true, 10, 0,
                    10, ?
                from entidades_fiscales e where e.id = ?
                """, pv.getId(), usuario, ventaNorte, centro.getEntidadFiscal().getId()), "otra entidad fiscal");
    }

    // --- Ayudas ---

    private int tomar(PuntoVenta p, String tipo) {
        return jdbc.queryForObject("""
                update punto_venta_comprobantes set ultimo_numero = ultimo_numero + 1
                where punto_venta_id = ? and tipo = ? and letra = 'X' returning ultimo_numero
                """, Integer.class, p.getId(), tipo);
    }

    private void fijarUltimo(PuntoVenta p, String tipo, int numero) {
        jdbc.update("""
                update punto_venta_comprobantes set ultimo_numero = ?
                where punto_venta_id = ? and tipo = ? and letra = 'X'
                """, numero, p.getId(), tipo);
    }

    /** Un comprobante X de $ 100 sin IVA, con su renglón, del emisor que corresponde. */
    private long emitirConNumero(PuntoVenta p, String tipo, int numero) {
        long id = emitirSinRenglones(p, tipo, numero);
        renglon(id, "100", "100.00");
        return id;
    }

    private long emitirSinRenglones(PuntoVenta p, String tipo, int numero) {
        Ambito a = p.getAmbito();
        return insertar(p, tipo, numero, a.getEntidadFiscal().getRazonSocial(),
                a.getEntidadFiscal().getCondicionIva().name(), null, 0);
    }

    /** Total 100 con precios finales; con iva > 0, neto 100 más ese IVA y precios sin IVA. */
    private long insertar(PuntoVenta p, String tipo, int numero, String razonSocial, String condicion, Long cliente,
            int iva) {
        return jdbc.queryForObject("""
                insert into comprobantes (tipo, letra, punto_venta_id, numero, emisor_razon_social, emisor_cuit,
                    emisor_condicion_iva, emisor_nombre, cliente_id, usuario_id, precios_con_iva, neto, iva, total)
                values (?, 'X', ?, ?, ?, ?, ?, 'Emisor', ?, ?, ?, 100, ?, ?) returning id
                """, Long.class, tipo, p.getId(), numero, razonSocial, p.getAmbito().getEntidadFiscal().getCuit(),
                condicion, cliente, usuario, iva == 0, iva, 100 + iva);
    }

    private void renglon(long comprobante, String precio, String subtotal) {
        jdbc.update("""
                insert into detalle_comprobante (comprobante_id, descripcion, cantidad, precio_unitario, alicuota_iva,
                                                 subtotal)
                values (?, 'Renglón', 1, ?::numeric, 0, ?::numeric)
                """, comprobante, precio, subtotal);
    }

    private void confirmarRestricciones() {
        jdbc.execute("set constraints all immediate");
        jdbc.execute("set constraints all deferred");
    }

    /** La base rechaza la operación con ese mensaje o esa restricción. Corre en un savepoint, como en entregas. */
    private void rechaza(ThrowingCallable operacion, String mensaje) {
        jdbc.execute("savepoint intento");
        try {
            assertThatThrownBy(operacion).isInstanceOf(DataAccessException.class).hasStackTraceContaining(mensaje);
        } finally {
            jdbc.execute("rollback to savepoint intento");
        }
    }
}
