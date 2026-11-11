package com.greenfire.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.clientes.Cliente;
import com.greenfire.comprobantes.Comprobante;
import com.greenfire.comprobantes.EmisorComprobantes;
import com.greenfire.comprobantes.EmisorComprobantes.Emision;
import com.greenfire.fiscal.TipoComprobante;
import com.greenfire.stock.UbicacionStock;

import jakarta.persistence.EntityManager;

/**
 * Reglas de las ventas que garantiza la base aunque se escriba por fuera de la aplicación (V14), una por test. Los
 * comprobantes se emiten con {@link EmisorComprobantes} (la base exige el número tomado); lo demás va por SQL. Las
 * verificaciones diferidas se corren con {@code set constraints all immediate}, porque la transacción del test no se
 * confirma, y "otra transacción" se simula borrando la marca que deja la venta al insertarse.
 */
@PruebaIntegracion
class VentaMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;
    @Autowired
    EmisorComprobantes emisor;
    @Autowired
    EntityManager em;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Articulo maceta;
    Articulo sustrato;
    Articulo turba;
    Articulo regadera;
    UbicacionStock principal;
    UbicacionStock principalFabrica;
    Cliente ana;
    Cliente mayorista;
    long caja;
    long usuario;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        datos.puntoVentaX(fabrica);
        datos.puntoVentaX(centro);
        datos.puntoVentaX(norte);
        Categoria categoria = datos.categoria("Varios");
        maceta = datos.articulo(centro, TipoArticulo.reventa, "Maceta", categoria);
        sustrato = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Sustrato", categoria);
        turba = datos.articulo(fabrica, TipoArticulo.insumo, "Turba", null);
        regadera = datos.articulo(norte, TipoArticulo.reventa, "Regadera", categoria);
        principal = datos.principal(centro);
        principalFabrica = datos.principal(fabrica);
        datos.stock(maceta, principal, "10");
        datos.stock(sustrato, principal, "10");
        datos.stock(sustrato, principalFabrica, "10");
        ana = datos.cliente(centro, "Ana");
        mayorista = datos.cliente(fabrica, "Vivero");
        usuario = datos.usuario("Ana").getId();
        caja = abrirCaja(centro);
    }

    // --- Ventas ---

    @Test
    void unaVentaNaceConfirmada() {
        long c = comprobante(centro, null, maceta, "1", "100");
        rechaza(() -> jdbc.update("""
                insert into ventas (ambito_id, usuario_id, sesion_caja_id, medio_pago, subtotal, descuento, total, estado,
                                    comprobante_id, anulada_por_usuario_id, anulada_at, motivo_anulacion)
                values (?, ?, ?, 'efectivo', 100, 0, 100, 'anulada', ?, ?, now(), 'x')
                """, centro.getId(), usuario, caja, c, usuario), "nace confirmada");
    }

    @Test
    void elClienteEsDelAmbitoDeLaVentaYNuncaElDeUnLocal() {
        Cliente deNorte = datos.cliente(norte, "Beto");
        long c = comprobante(centro, null, maceta, "1", "100");
        rechaza(() -> venta(centro, deNorte.getId(), caja, "efectivo", null, "100", "100", c), "mismo ámbito");
        Cliente local = datos.clienteDelLocal(centro);
        long cf = comprobante(fabrica, mayorista, sustrato, "1", "100");
        rechaza(() -> venta(fabrica, local.getId(), null, "efectivo", "2026-11-01", "100", "100", cf), "no el de un local");
    }

    @Test
    void enUnLocalLaVentaSeCobraEnSuCajaAbiertaYSinFechaDeCobro() {
        long c = comprobante(centro, null, maceta, "1", "100");
        rechaza(() -> venta(centro, null, null, "efectivo", null, "100", "100", c), "caja abierta");
        long deNorte = abrirCaja(norte);
        rechaza(() -> venta(centro, null, deNorte, "efectivo", null, "100", "100", c), "caja abierta");
        rechaza(() -> venta(centro, null, caja, "efectivo", "2026-11-01", "100", "100", c), "fecha de cobro");
        // la cuenta corriente no pasa por la caja
        rechaza(() -> venta(centro, null, caja, "cuenta_corriente", null, "100", "100", c),
                "ventas_cuenta_corriente_sin_caja");
        jdbc.update("update sesiones_caja set estado = 'cerrada', usuario_cierre_id = usuario_apertura_id, cerrada_at = now(), "
                + "total_efectivo = 0, total_transferencia = 0, total_billetera = 0, total_general = 0, "
                + "ingresos_efectivo = 0, egresos_efectivo = 0, efectivo_esperado = 0, efectivo_contado = 0, "
                + "diferencia = 0 where id = ?", caja);
        rechaza(() -> venta(centro, null, caja, "efectivo", null, "100", "100", c), "caja abierta");
    }

    @Test
    void laFabricaVendeAUnClienteSinCajaYConFechaDeCobroSalvoACuentaCorriente() {
        long sinCliente = comprobante(fabrica, null, sustrato, "1", "100");
        rechaza(() -> venta(fabrica, null, null, "efectivo", "2026-11-01", "100", "100", sinCliente), "siempre a un cliente");
        long c = comprobante(fabrica, mayorista, sustrato, "1", "100");
        rechaza(() -> venta(fabrica, mayorista.getId(), caja, "efectivo", "2026-11-01", "100", "100", c), "no tiene caja");
        rechaza(() -> venta(fabrica, mayorista.getId(), null, "efectivo", null, "100", "100", c), "fecha de cobro");
        rechaza(() -> venta(fabrica, mayorista.getId(), null, "cuenta_corriente", "2026-11-01", "100", "100", c),
                "fecha de cobro");
        venta(fabrica, mayorista.getId(), null, "transferencia", "2026-11-01", "100", "100", c);
    }

    @Test
    void elComprobanteEsUnComprobanteDeVentaDelAmbitoConElMismoClienteYTotal() {
        long c = comprobante(centro, null, maceta, "1", "100");
        rechaza(() -> venta(centro, null, caja, "efectivo", null, "90", "90", c), "el mismo total");
        rechaza(() -> venta(centro, ana.getId(), caja, "efectivo", null, "100", "100", c), "el mismo cliente");
        long deNorte = comprobante(norte, null, regadera, "1", "100");
        rechaza(() -> venta(centro, null, caja, "efectivo", null, "100", "100", deNorte), "del mismo ámbito");
        long nota = emisor.emitir(new Emision(centro, TipoComprobante.nota_credito, null, true,
                List.of(EmisorComprobantes.Renglon.de(maceta, BigDecimal.ONE, new BigDecimal("100"))),
                em.find(Comprobante.class, c), null, null, usuario)).getId();
        em.flush();
        rechaza(() -> venta(centro, null, caja, "efectivo", null, "100", "100", nota), "comprobante de venta");
        venta(centro, null, caja, "efectivo", null, "100", "100", c);
        rechaza(() -> venta(centro, null, caja, "efectivo", null, "100", "100", c), "uq_ventas_comprobante");
    }

    @Test
    void elTotalEsElSubtotalMenosElDescuento() {
        long c = comprobante(centro, null, maceta, "1", "90");
        rechaza(() -> jdbc.update("""
                insert into ventas (ambito_id, usuario_id, sesion_caja_id, medio_pago, subtotal, porcentaje_descuento,
                                    descuento, total, comprobante_id)
                values (?, ?, ?, 'efectivo', 100, 10, 5, 90, ?)
                """, centro.getId(), usuario, caja, c), "ventas_total_con_descuento");
        rechaza(() -> jdbc.update("""
                insert into ventas (ambito_id, usuario_id, sesion_caja_id, medio_pago, subtotal, porcentaje_descuento,
                                    descuento, total, comprobante_id)
                values (?, ?, ?, 'efectivo', 100, 101, 10, 90, ?)
                """, centro.getId(), usuario, caja, c), "ventas_porcentaje_descuento_check");
    }

    @Test
    void cadaVentaTieneRenglonesQueSumanSuTotal() {
        long sinRenglones = venta(centro, null, caja, "efectivo", null, "100", "100", comprobante(centro, null, maceta, "1", "100"));
        rechaza(this::confirmar, "no tiene renglones");
        renglon(sinRenglones, maceta, principal, "1", "100", "100", "100");
        confirmar();

        long mal = venta(centro, null, caja, "efectivo", null, "200", "200", comprobante(centro, null, maceta, "2", "100"));
        renglon(mal, maceta, principal, "1", "100", "100", "100");
        rechaza(this::confirmar, "no es la suma de sus renglones");
    }

    @Test
    void losRenglonesSeCarganConLaVentaYNoCambian() {
        long v = ventaCompleta("2", "100");
        olvidarMarca(v);
        rechaza(() -> renglon(v, sustrato, principal, "1", "100", "100", "100"), "se cargan al registrarla");
        rechaza(() -> jdbc.update("update detalle_ventas set cantidad = 1 where venta_id = ?", v), "no se modifica ni se borra");
        rechaza(() -> jdbc.update("delete from detalle_ventas where venta_id = ?", v), "no se modifica ni se borra");
        confirmar(); // un TRUNCATE no corre con verificaciones diferidas pendientes
        rechaza(() -> jdbc.execute("truncate detalle_ventas cascade"), "rechazada");
    }

    @Test
    void losRenglonesSonDelAmbitoNuncaInsumosYEnterosSiSeMidenEnUnidades() {
        long v = venta(centro, null, caja, "efectivo", null, "100", "100", comprobante(centro, null, maceta, "1", "100"));
        rechaza(() -> renglon(v, maceta, datos.principal(norte), "1", "100", "100", "100"), "del ámbito de la venta");
        rechaza(() -> renglon(v, regadera, principal, "1", "100", "100", "100"), "no puede tener stock");
        rechaza(() -> renglon(v, maceta, principal, "1.5", "100", "100", "150"), "debe ser entera");
        rechaza(() -> renglon(v, maceta, principal, "1", "100", "100", "99"), "detalle_ventas_subtotal");
        rechaza(() -> renglon(v, maceta, principal, "1", "100", "110", "110"), "detalle_ventas_descuento");
        long vf = venta(fabrica, mayorista.getId(), null, "efectivo", "2026-11-01", "100", "100",
                comprobante(fabrica, mayorista, sustrato, "1", "100"));
        rechaza(() -> renglon(vf, turba, principalFabrica, "1", "100", "100", "100"), "Los insumos no se venden");
        renglon(v, maceta, principal, "1", "100", "100", "100");
        rechaza(() -> renglon(v, maceta, principal, "1", "100", "100", "100"), "uq_detalle_ventas_renglon");
    }

    @Test
    void unaVentaSoloSeAnulaUnaVezConMotivoYNoSeBorra() {
        long v = ventaCompleta("1", "100");
        rechaza(() -> jdbc.update("update ventas set total = 50 where id = ?", v), "solo se anula");
        rechaza(() -> jdbc.update("update ventas set estado = 'anulada', anulada_por_usuario_id = ?, anulada_at = now() where id = ?",
                usuario, v), "ventas_anulacion_coherente");
        rechaza(() -> jdbc.update("""
                update ventas set estado = 'anulada', anulada_por_usuario_id = ?, anulada_at = now(),
                    motivo_anulacion = 'x', medio_pago = 'transferencia' where id = ?
                """, usuario, v), "solo cambian el estado");
        anular(v);
        rechaza(() -> anular(v), "solo se anula una venta confirmada");
        rechaza(() -> jdbc.update("delete from ventas where id = ?", v), "no se borran");
        confirmar(); // un TRUNCATE no corre con verificaciones diferidas pendientes
        rechaza(() -> jdbc.execute("truncate ventas cascade"), "rechazada");
    }

    @Test
    void unaVentaConDevolucionesNoSeAnula() {
        long v = ventaCompleta("2", "100");
        devolucion(v, "1");
        rechaza(() -> anular(v), "tiene devoluciones");
    }

    // --- Movimientos de stock ---

    @Test
    void cadaTipoDeMovimientoLlevaSuReferencia() {
        long v = ventaCompleta("1", "100");
        long entrega = entrega();
        // solo la restricción: sin los triggers, que rechazarían antes por otras reglas
        jdbc.execute("set local session_replication_role = replica");
        rechaza(() -> movimiento("venta", maceta, principal, "-1", null, null), "movimientos_stock_referencia_segun_tipo");
        rechaza(() -> movimiento("ajuste", maceta, principal, "1", v, null), "movimientos_stock_referencia_segun_tipo");
        rechaza(() -> movimiento("devolucion", maceta, principal, "1", null, null), "movimientos_stock_referencia_segun_tipo");
        rechaza(() -> movimiento("entrega_recepcion", maceta, principal, "1", v, null),
                "movimientos_stock_referencia_segun_tipo");
        rechaza(() -> movimiento("devolucion", sustrato, principalFabrica, "1", v, entrega),
                "movimientos_stock_referencia_segun_tipo");
        movimiento("devolucion", maceta, principal, "1", v, null);
        movimiento("anulacion", sustrato, principalFabrica, "1", null, entrega);
        jdbc.execute("set local session_replication_role = origin");
    }

    @Test
    void laVentaSacaLoVendidoUnaSolaVezAlRegistrarla() {
        long v = venta(centro, null, caja, "efectivo", null, "200", "200", comprobante(centro, null, maceta, "2", "100"));
        renglon(v, maceta, principal, "2", "100", "100", "200");
        rechaza(() -> movimiento("venta", maceta, principal, "-1", v, null), "saca la cantidad vendida");
        rechaza(() -> movimiento("venta", sustrato, principal, "-2", v, null), "no es de un renglón");
        movimiento("venta", maceta, principal, "-2", v, null);
        rechaza(() -> movimiento("venta", maceta, principal, "-2", v, null), "una sola vez");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("8");

        long otra = venta(centro, null, caja, "efectivo", null, "100", "100", comprobante(centro, null, sustrato, "1", "100"));
        renglon(otra, sustrato, principal, "1", "100", "100", "100");
        olvidarMarca(otra);
        rechaza(() -> movimiento("venta", sustrato, principal, "-1", otra, null), "una sola vez, al registrarla");
    }

    @Test
    void laAnulacionYLaDevolucionSumanSinPasarseDeLoVendido() {
        long v = ventaCompleta("3", "100");
        rechaza(() -> movimiento("anulacion", maceta, principal, "3", v, null), "de una venta anulada");
        rechaza(() -> movimiento("devolucion", maceta, principal, "-1", v, null), "suman stock");
        movimiento("devolucion", maceta, principal, "2", v, null);
        rechaza(() -> movimiento("devolucion", maceta, principal, "2", v, null), "supera lo vendido");

        long otra = ventaCompleta("1", "100");
        anular(otra);
        rechaza(() -> movimiento("devolucion", maceta, principal, "1", otra, null), "de una confirmada");
        movimiento("anulacion", maceta, principal, "1", otra, null);
        rechaza(() -> movimiento("anulacion", maceta, principal, "1", otra, null), "supera lo vendido");
    }

    // --- Devoluciones ---

    @Test
    void unaDevolucionEsDeUnaVentaConfirmadaConSuNotaDeCreditoPorElMismoTotal() {
        long v = ventaCompleta("2", "100");
        long nota = notaCredito(v, "1");
        rechaza(() -> insertarDevolucion(v, nota, "90", null), "por el mismo total");
        long otra = ventaCompleta("1", "100");
        rechaza(() -> insertarDevolucion(otra, nota, "100", null), "referencia al comprobante de la venta");
        rechaza(() -> insertarDevolucion(v, nota, "100", abrirCaja(norte)), "caja abierta del local de la venta");
        rechaza(() -> insertarDevolucion(v, comprobanteDe(v), "200", null), "nota de crédito");
        anular(otra);
        long notaDeOtra = notaCredito(otra, "1");
        rechaza(() -> insertarDevolucion(otra, notaDeOtra, "100", null), "no admite devoluciones");
    }

    @Test
    void loDevueltoDeUnRenglonNuncaSuperaLoVendido() {
        long v = ventaCompleta("2", "100");
        long renglon = renglonDe(v);
        long d = insertarDevolucion(v, notaCredito(v, "1"), "100", null);
        detalleDevolucion(d, renglon, "1", "100");
        rechaza(() -> detalleDevolucion(d, renglon, "1", "100"), "uq_detalle_devoluciones_venta_renglon");
        long d2 = insertarDevolucion(v, notaCredito(v, "1"), "100", null);
        rechaza(() -> detalleDevolucion(d2, renglon, "2", "200"), "supera lo vendido");
        rechaza(() -> detalleDevolucion(d2, renglon, "1", "90"), "el precio del renglón vendido");
        long otra = ventaCompleta("1", "100");
        rechaza(() -> detalleDevolucion(d2, renglonDe(otra), "1", "100"), "de la venta de la devolución");
        detalleDevolucion(d2, renglon, "1", "100");
        confirmar();
    }

    @Test
    void losRenglonesDeUnaDevolucionSeCarganConEllaYSumanSuTotal() {
        long v = ventaCompleta("2", "100");
        long d = insertarDevolucion(v, notaCredito(v, "1"), "100", null);
        rechaza(this::confirmar, "no tiene renglones");
        jdbc.queryForObject("select set_config('greenfire.devolucion_creando_' || ?, '', true)", String.class, d);
        rechaza(() -> detalleDevolucion(d, renglonDe(v), "1", "100"), "se cargan al registrarla");
        rechaza(() -> jdbc.update("update devoluciones_venta set motivo = 'otro' where id = ?", d), "no se modifican");
        rechaza(() -> jdbc.update("delete from devoluciones_venta where id = ?", d), "no se modifican");
    }

    // --- Caja ---

    @Test
    void elMovimientoDeCajaDeUnaVentaEsDeSuLocalYElCobroDeSuCaja() {
        long v = ventaCompleta("1", "100");
        rechaza(() -> movimientoCaja(caja, "manual", "ingreso", v), "movimientos_caja_venta_segun_origen");
        rechaza(() -> movimientoCaja(abrirCaja(norte), "venta", "ingreso", v), "va en la caja del local de la venta");
        movimientoCaja(caja, "venta", "ingreso", v);
        movimientoCaja(caja, "devolucion", "egreso", v);
    }

    @Test
    void elDescuentoMaximoDelEmpleadoEsUnPorcentaje() {
        rechaza(() -> jdbc.update("update ambitos set descuento_maximo_empleado = 101 where id = ?", centro.getId()),
                "descuento_maximo_empleado");
        rechaza(() -> jdbc.update("update ambitos set descuento_maximo_empleado = -1 where id = ?", centro.getId()),
                "descuento_maximo_empleado");
        assertThat(jdbc.queryForObject("select descuento_maximo_empleado from ambitos where id = ?", BigDecimal.class,
                centro.getId())).isEqualByComparingTo("0");
    }

    // --- Ayudas ---

    private long abrirCaja(Ambito local) {
        return jdbc.queryForObject("""
                insert into sesiones_caja (ambito_id, usuario_apertura_id, saldo_inicial) values (?, ?, 0) returning id
                """, Long.class, local.getId(), usuario);
    }

    private long comprobante(Ambito a, Cliente c, Articulo articulo, String cantidad, String precio) {
        long id = emisor.emitir(new Emision(a, TipoComprobante.comprobante_venta, c, true,
                List.of(EmisorComprobantes.Renglon.de(articulo, new BigDecimal(cantidad), new BigDecimal(precio))),
                usuario)).getId();
        em.flush();
        return id;
    }

    private long venta(Ambito a, Long cliente, Long sesion, String medio, String fechaCobro, String subtotal,
            String total, long comprobante) {
        return jdbc.queryForObject("""
                insert into ventas (ambito_id, usuario_id, cliente_id, sesion_caja_id, medio_pago, fecha_cobro, subtotal,
                                    descuento, total, comprobante_id)
                values (?, ?, ?, ?, ?, ?::date, ?::numeric, ?::numeric - ?::numeric, ?::numeric, ?) returning id
                """, Long.class, a.getId(), usuario, cliente, sesion, medio, fechaCobro, subtotal, subtotal, total, total,
                comprobante);
    }

    private void renglon(long venta, Articulo a, UbicacionStock u, String cantidad, String precioLista, String precio,
            String subtotal) {
        jdbc.update("""
                insert into detalle_ventas (venta_id, articulo_id, ubicacion_stock_id, cantidad, precio_lista,
                                            precio_unitario, alicuota_iva, subtotal)
                values (?, ?, ?, ?::numeric, ?::numeric, ?::numeric, 21, ?::numeric)
                """, venta, a.getId(), u.getId(), cantidad, precioLista, precio, subtotal);
    }

    /** Una venta de macetas del Centro, con su renglón y su movimiento de stock. */
    private long ventaCompleta(String cantidad, String precio) {
        String total = new BigDecimal(cantidad).multiply(new BigDecimal(precio)).toPlainString();
        long v = venta(centro, null, caja, "efectivo", null, total, total, comprobante(centro, null, maceta, cantidad, precio));
        renglon(v, maceta, principal, cantidad, precio, precio, total);
        movimiento("venta", maceta, principal, "-" + cantidad, v, null);
        return v;
    }

    private void movimiento(String tipo, Articulo a, UbicacionStock u, String cantidad, Long venta, Long entrega) {
        jdbc.update("""
                insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, motivo,
                                               venta_id, entrega_id)
                values (?, ?, ?, ?::numeric, ?, 'Prueba', ?, ?)
                """, a.getId(), u.getId(), tipo, cantidad, usuario, venta, entrega);
    }

    private void anular(long venta) {
        jdbc.update("""
                update ventas set estado = 'anulada', anulada_por_usuario_id = ?, anulada_at = now(), motivo_anulacion = 'x'
                where id = ?
                """, usuario, venta);
    }

    /** Simula que la transacción que registró la venta ya terminó. */
    private void olvidarMarca(long venta) {
        jdbc.queryForObject("select set_config('greenfire.venta_creando_' || ?, '', true)", String.class, venta);
    }

    private long comprobanteDe(long venta) {
        return jdbc.queryForObject("select comprobante_id from ventas where id = ?", Long.class, venta);
    }

    private long renglonDe(long venta) {
        return jdbc.queryForObject("select id from detalle_ventas where venta_id = ?", Long.class, venta);
    }

    /** Nota de crédito de macetas a 100, asociada al comprobante de la venta. */
    private long notaCredito(long venta, String cantidad) {
        long id = emisor.emitir(new Emision(centro, TipoComprobante.nota_credito, null, true,
                List.of(EmisorComprobantes.Renglon.de(maceta, new BigDecimal(cantidad), new BigDecimal("100"))),
                em.find(Comprobante.class, comprobanteDe(venta)), null, "Devolución", usuario)).getId();
        em.flush();
        return id;
    }

    private long insertarDevolucion(long venta, long notaCredito, String total, Long sesion) {
        return jdbc.queryForObject("""
                insert into devoluciones_venta (venta_id, usuario_id, motivo, total, nota_credito_id, sesion_caja_id)
                values (?, ?, 'Prueba', ?::numeric, ?, ?) returning id
                """, Long.class, venta, usuario, total, notaCredito, sesion);
    }

    private void detalleDevolucion(long devolucion, long renglon, String cantidad, String subtotal) {
        jdbc.update("""
                insert into detalle_devoluciones_venta (devolucion_id, detalle_venta_id, cantidad, subtotal)
                values (?, ?, ?::numeric, ?::numeric)
                """, devolucion, renglon, cantidad, subtotal);
    }

    /** Una devolución completa de macetas, con su nota de crédito, su renglón y su movimiento de stock. */
    private void devolucion(long venta, String cantidad) {
        String total = new BigDecimal(cantidad).multiply(new BigDecimal("100")).toPlainString();
        long d = insertarDevolucion(venta, notaCredito(venta, cantidad), total, null);
        detalleDevolucion(d, renglonDe(venta), cantidad, total);
        movimiento("devolucion", maceta, principal, cantidad, venta, null);
    }

    private void movimientoCaja(long sesion, String origen, String tipo, long venta) {
        jdbc.update("""
                insert into movimientos_caja (sesion_caja_id, usuario_id, origen, tipo, rubro, medio_pago, monto,
                                              observacion, venta_id)
                values (?, ?, ?, ?, case when ? = 'manual' then 'otro' end, 'efectivo', 10, 'Prueba', ?)
                """, sesion, usuario, origen, tipo, origen, venta);
    }

    /** Una entrega enviada de la fábrica al Centro, con un renglón de sustrato. */
    private long entrega() {
        long lista = jdbc.queryForObject("""
                insert into listas_precios (ambito_id, nombre, precios_con_iva, es_predeterminada)
                values (?, 'Locales', false, false) returning id
                """, Long.class, fabrica.getId());
        long id = jdbc.queryForObject("""
                insert into entregas (ambito_origen_id, ambito_destino_id, ubicacion_origen_id, lista_precios_id,
                                      usuario_envio_id, total)
                values (?, ?, ?, ?, ?, 100) returning id
                """, Long.class, fabrica.getId(), centro.getId(), principalFabrica.getId(), lista, usuario);
        jdbc.update("""
                insert into detalle_entregas (entrega_id, articulo_id, cantidad_enviada, precio_unitario, subtotal)
                values (?, ?, 1, 100, 100)
                """, id, sustrato.getId());
        return id;
    }

    private void confirmar() {
        jdbc.execute("set constraints all immediate");
        jdbc.execute("set constraints all deferred");
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
