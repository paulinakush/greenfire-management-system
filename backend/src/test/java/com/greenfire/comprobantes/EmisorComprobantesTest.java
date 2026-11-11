package com.greenfire.comprobantes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.clientes.Cliente;
import com.greenfire.clientes.TipoDocumento;
import com.greenfire.comprobantes.EmisorComprobantes.Emision;
import com.greenfire.comprobantes.EmisorComprobantes.Renglon;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.fiscal.ComprobantePermitido;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.PuntoVenta;
import com.greenfire.fiscal.PuntoVentaRepository;
import com.greenfire.fiscal.TipoComprobante;

import jakarta.persistence.EntityManager;

/**
 * {@link EmisorComprobantes} y {@link NumeradorComprobantes}, directamente, sin pasar por la API (no hay alta
 * directa de comprobantes). Las restricciones diferidas de la base se comprueban con {@link #confirmarRestricciones},
 * porque la transacción del test no se confirma.
 */
@PruebaIntegracion
class EmisorComprobantesTest {

    @Autowired
    EmisorComprobantes emisor;
    @Autowired
    NumeradorComprobantes numerador;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;
    @Autowired
    PuntoVentaRepository puntosVenta;
    @Autowired
    ApplicationEvents eventos;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Articulo coco;
    Articulo maceta;
    Articulo libro;
    Long usuario;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica(); // responsable inscripta
        centro = datos.local("Centro"); // monotributista
        norte = datos.local("Norte");
        Categoria categoria = datos.categoria("Sustratos");
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", categoria); // 21 %
        maceta = datos.articulo(centro, TipoArticulo.reventa, "Maceta", categoria);
        libro = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Libro de cultivo", categoria);
        libro.setAlicuotaIva(new BigDecimal("10.5"));
        usuario = datos.usuario("Ana").getId();
    }

    // --- Numeración ---

    @Test
    void numeraCorrelativoPorPuntoDeVentaTipoYLetra() {
        datos.puntoVentaX(centro);
        datos.puntoVentaX(norte);

        assertThat(List.of(venta(centro).getNumero(), venta(centro).getNumero(), venta(centro).getNumero()))
                .containsExactly(1, 2, 3);
        assertThat(emisor.emitir(new Emision(centro, TipoComprobante.recibo, null, true,
                List.of(Renglon.libre("Pago", new BigDecimal("100"), BigDecimal.ZERO)), usuario)).getNumero())
                .isEqualTo(1);
        assertThat(venta(norte).getNumero()).isEqualTo(1);

        assertThat(ultimoNumero(centro, TipoComprobante.comprobante_venta)).isEqualTo(3);
        assertThat(ultimoNumero(centro, TipoComprobante.recibo)).isEqualTo(1);
        assertThat(ultimoNumero(centro, TipoComprobante.remito)).isZero();
        confirmarRestricciones();
    }

    @Test
    void sinPuntoDeVentaActivoQueLoEmitaFallaConUnMensajeClaro() {
        PuntoVenta soloRecibos = datos.puntoVentaX(centro, 1, TipoComprobante.recibo);
        PuntoVenta inactivo = datos.puntoVentaX(centro, 2, TipoComprobante.comprobante_venta);
        inactivo.setActivo(false);
        em.flush();

        assertThatThrownBy(() -> venta(centro))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessage("'Centro' no tiene un punto de venta activo que emita comprobante de venta X. "
                        + "Pedile al administrador técnico que lo configure.");
        assertThat(ultimoNumero(soloRecibos, TipoComprobante.comprobante_venta)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from comprobantes where punto_venta_id in (?, ?)",
                Integer.class, soloRecibos.getId(), inactivo.getId())).isZero();
    }

    @Test
    void conVariosPuntosDeVentaUsaElDeNumeroMasBajo() {
        datos.puntoVentaX(centro, 7, TipoComprobante.comprobante_venta);
        PuntoVenta tres = datos.puntoVentaX(centro, 3, TipoComprobante.comprobante_venta);

        Comprobante c = venta(centro);

        assertThat(c.getPuntoVenta().getId()).isEqualTo(tres.getId());
        assertThat(c.numeroCompleto()).isEqualTo("00003-00000001");
    }

    @Test
    void editarLosTiposDelPuntoDeVentaNoReiniciaLaNumeracion() {
        PuntoVenta pv = datos.puntoVentaX(centro, 1, TipoComprobante.comprobante_venta, TipoComprobante.recibo);
        venta(centro);
        venta(centro);

        // se suma un tipo: las filas de los que quedan no se tocan y conservan su número
        Long filaVentas = filaDe(pv, TipoComprobante.comprobante_venta);
        pv.setComprobantes(Set.of(new ComprobantePermitido(TipoComprobante.comprobante_venta, LetraComprobante.X),
                new ComprobantePermitido(TipoComprobante.recibo, LetraComprobante.X),
                new ComprobantePermitido(TipoComprobante.presupuesto, LetraComprobante.X)));
        em.flush();
        assertThat(filaDe(pv, TipoComprobante.comprobante_venta)).isEqualTo(filaVentas);
        assertThat(venta(centro).getNumero()).isEqualTo(3);

        // se quita y se vuelve a agregar: la base retoma desde el último emitido
        pv.setComprobantes(Set.of(new ComprobantePermitido(TipoComprobante.recibo, LetraComprobante.X)));
        em.flush();
        assertThatThrownBy(() -> venta(centro)).isInstanceOf(ReglaNegocioException.class);
        pv.setComprobantes(Set.of(new ComprobantePermitido(TipoComprobante.recibo, LetraComprobante.X),
                new ComprobantePermitido(TipoComprobante.comprobante_venta, LetraComprobante.X)));
        em.flush();
        assertThat(ultimoNumero(centro, TipoComprobante.comprobante_venta)).isEqualTo(3);
        assertThat(venta(centro).getNumero()).isEqualTo(4);
        confirmarRestricciones();
    }

    // --- Datos copiados ---

    @Test
    void copiaLosDatosDelEmisorYDelReceptorAlEmitir() {
        datos.puntoVentaX(centro);
        centro.setDireccion("Mitre 100");
        centro.getEntidadFiscal().setDomicilioFiscal("Mitre 100, Salta");
        Cliente cliente = datos.cliente(centro, "Juan");
        cliente.setRazonSocial("Juan Pérez SA");
        cliente.setDocumentoTipo(TipoDocumento.cuit);
        cliente.setDocumentoNumero(Datos.cuit());
        cliente.setCondicionIva(CondicionIva.responsable_inscripto);
        cliente.setDireccion("Belgrano 5");
        em.flush();

        Comprobante c = emisor.emitir(new Emision(centro, TipoComprobante.comprobante_venta, cliente, true,
                List.of(Renglon.de(maceta, BigDecimal.ONE, new BigDecimal("10"))), usuario));

        // después cambian el local, su entidad y el cliente
        String cuitEmisor = centro.getEntidadFiscal().getCuit();
        String cuitCliente = cliente.getDocumentoNumero();
        centro.setNombre("Centro nuevo");
        centro.getEntidadFiscal().setRazonSocial("Otra razón social");
        cliente.setRazonSocial("Otro nombre");
        cliente.setDireccion("Otra dirección");
        em.flush();
        em.clear();

        Map<String, Object> fila = jdbc.queryForMap("select * from comprobantes where id = ?", c.getId());
        assertThat(fila).containsEntry("emisor_nombre", "Centro")
                .containsEntry("emisor_razon_social", "Entidad monotributo")
                .containsEntry("emisor_cuit", cuitEmisor)
                .containsEntry("emisor_condicion_iva", "monotributo")
                .containsEntry("emisor_domicilio_fiscal", "Mitre 100, Salta")
                .containsEntry("emisor_direccion", "Mitre 100")
                .containsEntry("cliente_id", cliente.getId())
                .containsEntry("receptor_razon_social", "Juan Pérez SA")
                .containsEntry("receptor_documento_tipo", "cuit")
                .containsEntry("receptor_documento", cuitCliente)
                .containsEntry("receptor_condicion_iva", "responsable_inscripto")
                .containsEntry("receptor_domicilio", "Belgrano 5")
                .containsEntry("letra", "X")
                .containsEntry("estado", "emitido");
    }

    @Test
    void sinClienteElReceptorEsConsumidorFinal() {
        datos.puntoVentaX(centro);

        Comprobante c = venta(centro);

        assertThat(c.getCliente()).isNull();
        assertThat(c.getReceptorRazonSocial()).isNull();
        assertThat(c.getReceptorCondicionIva()).isEqualTo(CondicionIva.consumidor_final);
        assertThat(c.leyenda()).isEqualTo("Documento no válido como factura");
    }

    // --- Importes ---

    @Test
    void unEmisorResponsableInscriptoDiscriminaElIvaDePreciosConIva() {
        datos.puntoVentaX(fabrica);

        Comprobante c = emisor.emitir(new Emision(fabrica, TipoComprobante.comprobante_venta, null, true, List.of(
                Renglon.de(coco, new BigDecimal("2"), new BigDecimal("1210")),
                Renglon.de(libro, BigDecimal.ONE, new BigDecimal("110.50"))), usuario));

        assertThat(c.discriminaIva()).isTrue();
        assertThat(c.isPreciosConIva()).isTrue();
        assertThat(c.getTotal()).isEqualByComparingTo("2530.50");
        assertThat(c.getIva()).isEqualByComparingTo("430.50"); // 420 del 21 % y 10,50 del 10,5 %
        assertThat(c.getNeto()).isEqualByComparingTo("2100.00");
        assertThat(CalculoComprobante.calcular(c).porAlicuota())
                .extracting(a -> a.alicuota().toPlainString(), a -> a.iva().toPlainString())
                .containsExactly(Tuple.tuple("10.5", "10.50"),
                        Tuple.tuple("21", "420.00"));
        confirmarRestricciones();
    }

    @Test
    void unEmisorResponsableInscriptoSumaElIvaAPreciosSinIva() {
        datos.puntoVentaX(fabrica);
        Cliente local = datos.clienteDelLocal(centro);

        Comprobante c = emisor.emitir(new Emision(fabrica, TipoComprobante.remito, local, false,
                List.of(Renglon.de(coco, new BigDecimal("3"), new BigDecimal("1000"))), usuario));

        assertThat(c.isPreciosConIva()).isFalse();
        assertThat(c.getNeto()).isEqualByComparingTo("3000");
        assertThat(c.getIva()).isEqualByComparingTo("630");
        assertThat(c.getTotal()).isEqualByComparingTo("3630");
        assertThat(c.getRenglones()).singleElement()
                .satisfies(r -> assertThat(r.getSubtotal()).isEqualByComparingTo("3000"));
        confirmarRestricciones();
    }

    @Test
    void unEmisorQueNoDiscriminaIvaNoMuestraIvaYSusPreciosSonFinales() {
        datos.puntoVentaX(centro); // monotributista

        Comprobante c = emisor.emitir(new Emision(centro, TipoComprobante.comprobante_venta, null, false, List.of(
                Renglon.de(maceta, new BigDecimal("3"), new BigDecimal("333.33")),
                Renglon.de(coco, BigDecimal.ONE, new BigDecimal("1210"))), usuario));

        assertThat(c.discriminaIva()).isFalse();
        assertThat(c.isPreciosConIva()).isTrue();
        assertThat(c.getIva()).isEqualByComparingTo("0");
        assertThat(c.getNeto()).isEqualByComparingTo("2209.99");
        assertThat(c.getTotal()).isEqualByComparingTo("2209.99");
        assertThat(CalculoComprobante.calcular(c).porAlicuota()).isEmpty();
        confirmarRestricciones();
    }

    // --- Validaciones ---

    @Test
    void rechazaLoQueNoSePuedeEmitirAntesDeTomarElNumero() {
        datos.puntoVentaX(centro);
        datos.puntoVentaX(norte);
        Cliente deNorte = datos.cliente(norte, "Ajeno");
        Articulo macetaNorte = datos.articulo(norte, TipoArticulo.reventa, "Maceta Norte", datos.categoria("x"));
        List<Renglon> uno = List.of(Renglon.de(maceta, BigDecimal.ONE, BigDecimal.TEN));

        rechaza(new Emision(centro, TipoComprobante.factura, null, true, uno, usuario),
                "Las facturas se emiten con ARCA, en la etapa 6.");
        rechaza(new Emision(centro, TipoComprobante.comprobante_venta, null, true, List.of(), usuario),
                "El comprobante no tiene renglones.");
        rechaza(new Emision(centro, TipoComprobante.comprobante_venta, deNorte, true, uno, usuario),
                "El cliente no es de Centro.");
        rechaza(new Emision(centro, TipoComprobante.comprobante_venta, null, true,
                List.of(Renglon.de(macetaNorte, BigDecimal.ONE, BigDecimal.TEN)), usuario),
                "'Maceta Norte' no es un artículo de Centro.");
        rechaza(new Emision(centro, TipoComprobante.comprobante_venta, null, true,
                List.of(Renglon.de(maceta, BigDecimal.ZERO, BigDecimal.TEN)), usuario),
                "La cantidad de 'Maceta' tiene que ser mayor que cero.");
        rechaza(new Emision(centro, TipoComprobante.comprobante_venta, null, true,
                List.of(Renglon.de(maceta, BigDecimal.ONE, new BigDecimal("-1"))), usuario),
                "El precio de 'Maceta' no puede ser negativo.");
        rechaza(new Emision(centro, TipoComprobante.recibo, null, true,
                List.of(Renglon.libre(" ", BigDecimal.TEN, BigDecimal.ZERO)), usuario),
                "Cada renglón lleva un artículo o una descripción.");

        assertThat(ultimoNumero(centro, TipoComprobante.comprobante_venta)).isZero();
        assertThat(ultimoNumero(centro, TipoComprobante.recibo)).isZero();
    }

    @Test
    void lasNotasDeCreditoYDebitoXSeAsocianAUnComprobanteDeLaMismaEntidad() {
        datos.puntoVentaX(centro);
        datos.puntoVentaX(norte);
        Comprobante venta = venta(centro);
        Comprobante ventaNorte = venta(norte);
        List<Renglon> devolucion = List.of(Renglon.de(maceta, BigDecimal.ONE, BigDecimal.TEN));

        Comprobante nc = emisor.emitir(new Emision(centro, TipoComprobante.nota_credito, null, true, devolucion,
                venta, null, "Devolución", usuario));
        Comprobante nd = emisor.emitir(new Emision(centro, TipoComprobante.nota_debito, null, true,
                List.of(Renglon.libre("Ajuste", new BigDecimal("50"), BigDecimal.ZERO)), venta, null, null, usuario));

        assertThat(nc.getLetra()).isEqualTo(LetraComprobante.X);
        assertThat(nc.getNumero()).isEqualTo(1);
        assertThat(nc.getAsociado().getId()).isEqualTo(venta.getId());
        assertThat(nd.getNumero()).isEqualTo(1);
        assertThat(nd.leyenda()).isEqualTo("Documento no válido como factura");

        rechaza(new Emision(centro, TipoComprobante.nota_credito, null, true, devolucion, ventaNorte, null, null,
                usuario), "El comprobante asociado es de otra entidad fiscal.");
        rechaza(new Emision(centro, TipoComprobante.comprobante_venta, null, true, devolucion, venta, null, null,
                usuario), "Solo una nota de crédito o de débito lleva un comprobante asociado.");
        confirmarRestricciones();
    }

    // --- Anulación ---

    @Test
    void anularDejaElComprobanteAnuladoConMotivoUnaSolaVez() {
        datos.puntoVentaX(centro);
        Comprobante c = venta(centro);
        Long otro = datos.usuario("Beto").getId();

        assertThatThrownBy(() -> emisor.anular(c, "  ", otro)).isInstanceOf(ReglaNegocioException.class)
                .hasMessage("Para anular un comprobante hace falta un motivo.");
        emisor.anular(c, " Venta anulada ", otro);

        Map<String, Object> fila = jdbc.queryForMap("select * from comprobantes where id = ?", c.getId());
        assertThat(fila).containsEntry("estado", "anulado").containsEntry("anulado_por_usuario_id", otro)
                .containsEntry("motivo_anulacion", "Venta anulada");
        assertThat(fila.get("anulado_at")).isNotNull();
        assertThatThrownBy(() -> emisor.anular(c, "Otra vez", otro)).isInstanceOf(ReglaNegocioException.class)
                .hasMessage("El comprobante Comprobante de venta X 00001-00000001 ya está anulado.");
        // el número no se libera
        assertThat(venta(centro).getNumero()).isEqualTo(2);
    }

    @Test
    void emitirYAnularQuedanEnAuditoriaConElAmbitoAfectado() {
        datos.puntoVentaX(centro);
        Comprobante c = venta(centro);
        emisor.anular(c, "Error de carga", usuario);

        List<EventoAuditoria> registros = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.entidad().equals("comprobante")).toList();
        assertThat(registros).extracting(EventoAuditoria::accion).containsExactly("emitir", "anular");
        assertThat(registros).allSatisfy(e -> {
            assertThat(e.entidadId()).isEqualTo(c.getId());
            assertThat(e.ambitosAfectados()).containsExactly(centro.getId());
        });
        Map<String, ?> emision = registros.get(0).detalle();
        assertThat(emision.get("numero")).isEqualTo(1);
        assertThat(emision.get("puntoVenta")).isEqualTo(1);
        assertThat(emision.get("tipo")).isEqualTo(TipoComprobante.comprobante_venta);
        assertThat(registros.get(1).detalle().get("motivo")).isEqualTo("Error de carga");
    }

    // --- Ayudas ---

    private Comprobante venta(Ambito ambito) {
        Articulo articulo = ambito.getTipo() == TipoAmbito.fabrica ? coco
                : ambito.getId().equals(centro.getId()) ? maceta : coco;
        return emisor.emitir(new Emision(ambito, TipoComprobante.comprobante_venta, null, true,
                List.of(Renglon.de(articulo, BigDecimal.ONE, new BigDecimal("100"))), usuario));
    }

    private void rechaza(Emision emision, String mensaje) {
        assertThatThrownBy(() -> emisor.emitir(emision)).isInstanceOf(ReglaNegocioException.class)
                .hasMessage(mensaje);
    }

    private Integer ultimoNumero(Ambito ambito, TipoComprobante tipo) {
        em.flush();
        return jdbc.query("""
                select pvc.ultimo_numero from punto_venta_comprobantes pvc
                join puntos_venta pv on pv.id = pvc.punto_venta_id
                where pv.ambito_id = ? and pvc.tipo = ? and pvc.letra = 'X' order by pv.numero limit 1
                """, rs -> rs.next() ? rs.getInt(1) : null, ambito.getId(), tipo.name());
    }

    private Integer ultimoNumero(PuntoVenta pv, TipoComprobante tipo) {
        em.flush();
        return jdbc.query("select ultimo_numero from punto_venta_comprobantes where punto_venta_id = ? and tipo = ?",
                rs -> rs.next() ? rs.getInt(1) : null, pv.getId(), tipo.name());
    }

    private Long filaDe(PuntoVenta pv, TipoComprobante tipo) {
        em.flush();
        return jdbc.queryForObject("select id from punto_venta_comprobantes where punto_venta_id = ? and tipo = ?",
                Long.class, pv.getId(), tipo.name());
    }

    /** Corre ya las verificaciones diferidas (total de cada comprobante y número usado), como al confirmar. */
    private void confirmarRestricciones() {
        em.flush();
        jdbc.execute("set constraints all immediate");
        jdbc.execute("set constraints all deferred");
    }
}
