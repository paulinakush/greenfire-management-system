package com.greenfire.ventas;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.clientes.Cliente;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.seguridad.Perfil;
import com.greenfire.stock.UbicacionStock;

import jakarta.persistence.EntityManager;

/**
 * Ventas por la API: precio, IVA y descuento; stock, caja y comprobante; anulación y devolución; qué puede hacer y ver
 * cada perfil en cada ámbito. Lo que tiene que revertirse de verdad (una venta que falla a mitad de camino) está en
 * {@link VentaConcurrenciaTest}.
 */
@PruebaIntegracion
class VentaApiTest {

    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    EntityManager em;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    ApplicationEvents eventos;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Categoria categoria;
    Articulo sustrato;
    Articulo maceta;
    ListaPrecios listaCentro;
    UbicacionStock principal;
    RequestPostProcessor dueno;
    RequestPostProcessor empleado;
    RequestPostProcessor duenoFabrica;
    Cliente mayorista;

    @BeforeEach
    void datos() throws Exception {
        fabrica = datos.fabrica();
        datos.puntoVentaX(fabrica);
        centro = datos.local("Centro");
        datos.puntoVentaX(centro);
        norte = datos.local("Norte");
        datos.puntoVentaX(norte);
        categoria = datos.categoria("Sustratos");
        sustrato = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Sustrato 80 dm3", categoria);
        maceta = datos.articulo(centro, TipoArticulo.reventa, "Maceta 20 cm", categoria);
        listaCentro = datos.lista(centro, "Mostrador", true, true);
        datos.precio(listaCentro, sustrato, "3000");
        datos.precio(listaCentro, maceta, "1500");
        datos.costo(sustrato, centro, "2000");
        datos.costo(maceta, centro, "900");
        principal = datos.principal(centro);
        datos.stock(sustrato, principal, "10");
        datos.stock(maceta, principal, "5");
        dueno = datos.en(centro, Perfil.dueno_local);
        empleado = datos.en(centro, Perfil.empleado_local);

        // la fábrica le vende al por mayor, con su lista mayorista (sin IVA: la fábrica es responsable inscripta)
        ListaPrecios mayoristaLista = datos.lista(fabrica, "Mayorista", false, false);
        datos.precio(mayoristaLista, sustrato, "2000");
        datos.costo(sustrato, fabrica, "1200");
        datos.stock(sustrato, datos.principal(fabrica), "100");
        mayorista = datos.cliente(fabrica, "Vivero Las Flores");
        mayorista.setListaPrecios(mayoristaLista);
        duenoFabrica = datos.en(fabrica, Perfil.dueno_fabrica);
    }

    // --- Venta presencial ---

    @Test
    void unaVentaEnEfectivoDescuentaElStockEmiteElComprobanteYSumaALaCaja() throws Exception {
        long caja = abrirCaja(empleado);
        String respuesta = vender(empleado, renglones("efectivo", null, sustrato, "2", maceta, "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("confirmada"))
                .andExpect(jsonPath("$.subtotal").value(7500.00))
                .andExpect(jsonPath("$.descuento").value(0))
                .andExpect(jsonPath("$.total").value(7500.00))
                .andExpect(jsonPath("$.comprobante.tipo").value("comprobante_venta"))
                .andExpect(jsonPath("$.comprobante.letra").value("X"))
                .andExpect(jsonPath("$.comprobante.numero").value("00001-00000001"))
                .andExpect(jsonPath("$.renglones.length()").value(2))
                .andExpect(jsonPath("$.renglones[0].ubicacion").value("Principal"))
                .andReturn().getResponse().getContentAsString();
        // el empleado no recibe costos ni márgenes
        assertThat(respuesta.toLowerCase()).doesNotContain("costo", "margen");
        long id = mapper.readTree(respuesta).get("id").asLong();

        assertThat(datos.cantidad(sustrato, principal)).isEqualByComparingTo("8");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("4");
        assertThat(jdbc.queryForList("select cantidad from movimientos_stock where venta_id = ? and tipo = 'venta' order by cantidad",
                BigDecimal.class, id)).extracting(BigDecimal::intValue).containsExactly(-2, -1);
        Map<String, Object> fila = jdbc.queryForMap("""
                select v.total, v.sesion_caja_id, c.total as total_comprobante, c.estado
                from ventas v join comprobantes c on c.id = v.comprobante_id where v.id = ?
                """, id);
        assertThat(fila.get("sesion_caja_id")).isEqualTo(caja);
        assertThat((BigDecimal) fila.get("total_comprobante")).isEqualByComparingTo((BigDecimal) fila.get("total"));
        // suma al cierre de caja
        mvc.perform(get("/api/caja/actual").with(dueno))
                .andExpect(jsonPath("$.totales.ventasEfectivo").value(7500.00))
                .andExpect(jsonPath("$.totales.efectivoEsperado").value(7500.00))
                .andExpect(jsonPath("$.movimientos[0].ventaId").value(id));
    }

    @Test
    void elDuenoVeCostoYMargenYElEmpleadoNo() throws Exception {
        abrirCaja(dueno);
        long id = id(vender(dueno, renglones("transferencia", null, sustrato, "1", maceta, "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.costoTotal").value(3800.00))
                // neto = total (el local no discrimina IVA): (6000 - 3800) / 6000
                .andExpect(jsonPath("$.margen").value(0.3667))
                .andExpect(jsonPath("$.renglones[0].costoUnitario").value(2000.00))
                .andExpect(jsonPath("$.renglones[0].margen").value(0.3333)));

        String delEmpleado = mvc.perform(get("/api/ventas/" + id).with(empleado)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6000.00))
                .andReturn().getResponse().getContentAsString();
        assertThat(delEmpleado.toLowerCase()).doesNotContain("costo", "margen");
        String lista = mvc.perform(get("/api/ventas").with(empleado)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(lista.toLowerCase()).doesNotContain("costo", "margen");
        mvc.perform(get("/api/ventas").with(dueno)).andExpect(jsonPath("$.ventas[0].margen").value(0.3667));
        // la transferencia no suma al efectivo
        mvc.perform(get("/api/caja/actual").with(dueno))
                .andExpect(jsonPath("$.totales.totalTransferencia").value(6000.00))
                .andExpect(jsonPath("$.totales.efectivoEsperado").value(0));
    }

    @Test
    void conIvaYDescuentoElTotalDeLaVentaEsElDelComprobante() throws Exception {
        // un local responsable inscripto, con una lista sin IVA: al precio de lista se le suma el IVA
        Ambito ri = datos.ambito(TipoAmbito.local, "Local RI", CondicionIva.responsable_inscripto);
        datos.puntoVentaX(ri);
        ListaPrecios sinIva = datos.lista(ri, "Mostrador sin IVA", false, true);
        Articulo tierra = datos.articulo(ri, TipoArticulo.reventa, "Tierra", categoria);
        Articulo perlita = datos.articulo(ri, TipoArticulo.reventa, "Perlita", categoria);
        perlita.setAlicuotaIva(new BigDecimal("10.5"));
        perlita.setUnidadMedida(UnidadMedida.kg);
        datos.precio(sinIva, tierra, "33.33");
        datos.precio(sinIva, perlita, "17.77");
        datos.stock(tierra, datos.principal(ri), "50");
        datos.stock(perlita, datos.principal(ri), "50");
        RequestPostProcessor duenoRi = datos.en(ri, Perfil.dueno_local);
        abrirCaja(duenoRi);

        // tierra: 33.33 + 21 % = 40.33; con 7.5 %: 37.31 (37.30525); por 3 = 111.93
        // perlita: 17.77 + 10.5 % = 19.64; con 7.5 %: 18.17 (18.167); por 2.335 = 42.43 (42.42695)
        String cuerpo = """
                {"medioPago": "efectivo", "porcentajeDescuento": 7.5, "renglones": [
                    {"articuloId": %d, "cantidad": 3}, {"articuloId": %d, "cantidad": 2.335}]}
                """.formatted(tierra.getId(), perlita.getId());
        long id = id(vender(duenoRi, cuerpo).andExpect(status().isCreated())
                .andExpect(jsonPath("$.renglones[0].precioLista").value(40.33))
                .andExpect(jsonPath("$.renglones[0].precioUnitario").value(37.31))
                .andExpect(jsonPath("$.renglones[0].subtotal").value(111.93))
                .andExpect(jsonPath("$.renglones[0].alicuotaIva").value(21.00))
                .andExpect(jsonPath("$.renglones[1].precioLista").value(19.64))
                .andExpect(jsonPath("$.renglones[1].precioUnitario").value(18.17))
                .andExpect(jsonPath("$.renglones[1].subtotal").value(42.43))
                .andExpect(jsonPath("$.renglones[1].alicuotaIva").value(10.50))
                // subtotal a precio de lista: 120.99 + 45.86
                .andExpect(jsonPath("$.subtotal").value(166.85))
                .andExpect(jsonPath("$.total").value(154.36))
                .andExpect(jsonPath("$.descuento").value(12.49))
                .andExpect(jsonPath("$.porcentajeDescuento").value(7.50)));

        Map<String, Object> c = jdbc.queryForMap("""
                select c.total, c.neto, c.iva, c.precios_con_iva, v.total as total_venta
                from ventas v join comprobantes c on c.id = v.comprobante_id where v.id = ?
                """, id);
        assertThat((BigDecimal) c.get("total")).isEqualByComparingTo((BigDecimal) c.get("total_venta"))
                .isEqualByComparingTo("154.36");
        assertThat(c.get("precios_con_iva")).isEqualTo(true);
        // IVA sacado de los precios finales: 111.93 × 21 / 121 = 19.43; 42.43 × 10.5 / 110.5 = 4.03
        assertThat((BigDecimal) c.get("iva")).isEqualByComparingTo("23.46");
        assertThat((BigDecimal) c.get("neto")).isEqualByComparingTo("130.90");
    }

    @Test
    void elPrecioSaleDeLaListaDelClienteSiTieneUna() throws Exception {
        ListaPrecios amigos = datos.lista(centro, "Amigos", true, false);
        datos.precio(amigos, maceta, "1000");
        Cliente ana = datos.cliente(centro, "Ana");
        ana.setListaPrecios(amigos);
        abrirCaja(empleado);

        vender(empleado, renglones("efectivo", ana.getId(), maceta, "1")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(1000.00))
                .andExpect(jsonPath("$.cliente.nombre").value("Ana"));
        // sin precio en la lista del cliente, no se vende
        vender(empleado, renglones("efectivo", ana.getId(), sustrato, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("'Sustrato 80 dm3' no tiene precio en la lista 'Amigos'."));
        vender(empleado, renglones("efectivo", null, maceta, "1")).andExpect(jsonPath("$.total").value(1500.00));
    }

    @Test
    void elEmpleadoAplicaHastaElDescuentoMaximoDeSuLocalYElDuenoSinTope() throws Exception {
        abrirCaja(empleado);
        vender(empleado, conDescuento("1")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("El descuento máximo que puede aplicar el empleado en este local es 0 %."));

        mvc.perform(json(put("/api/ambito/condiciones-venta"), empleado, "{\"descuentoMaximoEmpleado\": 10}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/ambito/condiciones-venta"), dueno, "{\"descuentoMaximoEmpleado\": 101}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/ambito/condiciones-venta"), dueno, "{\"descuentoMaximoEmpleado\": 10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.descuentoMaximoEmpleado").value(10.00));
        mvc.perform(get("/api/ambito/condiciones-venta").with(empleado))
                .andExpect(jsonPath("$.descuentoMaximoEmpleado").value(10.00));
        EventoAuditoria edicion = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals("editar_condiciones_venta")).findFirst().orElseThrow();
        assertThat(edicion.detalle().get("descuentoMaximoEmpleado"))
                .isEqualTo(Map.of("antes", new BigDecimal("0.00"), "despues", new BigDecimal("10.00")));

        vender(empleado, conDescuento("10.01")).andExpect(status().isForbidden());
        vender(empleado, conDescuento("10")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(1350.00));
        vender(dueno, conDescuento("60")).andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(600.00));
        vender(dueno, conDescuento("100.5")).andExpect(status().isBadRequest());
        vender(dueno, conDescuento("1.555")).andExpect(status().isBadRequest());
    }

    @Test
    void sinStockSinCajaOACuentaCorrienteNoSeVende() throws Exception {
        vender(empleado, renglones("efectivo", null, maceta, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("No hay una caja abierta en este local."));
        abrirCaja(empleado);
        vender(empleado, renglones("efectivo", null, sustrato, "1", maceta, "6")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Stock insuficiente: 'Maceta 20 cm' en 'Principal' tiene 5 y se quieren sacar 6."));
        vender(empleado, renglones("cuenta_corriente", null, maceta, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(VentaService.SIN_CUENTA_CORRIENTE));

        assertThat(jdbc.queryForObject("select count(*) from ventas where ambito_id = ?", Integer.class, centro.getId()))
                .isZero();
        assertThat(datos.cantidad(sustrato, principal)).isEqualByComparingTo("10");
    }

    @Test
    void lasCantidadesYLosRenglonesTienenLimites() throws Exception {
        abrirCaja(empleado);
        vender(empleado, renglones("efectivo", null, maceta, "1.5")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("'Maceta 20 cm' se mide en unidades: la cantidad debe ser entera."));
        vender(empleado, renglones("efectivo", null, maceta, "0")).andExpect(status().isBadRequest());
        vender(empleado, renglones("efectivo", null, maceta, "10001")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("La cantidad de un renglón admite hasta 10000."));
        vender(empleado, renglones("efectivo", null, maceta, "1", maceta, "1")).andExpect(status().isBadRequest());
        vender(empleado, "{\"medioPago\": \"efectivo\", \"renglones\": []}").andExpect(status().isBadRequest());
        String muchos = IntStream.range(0, VentaService.MAXIMO_RENGLONES + 1)
                .mapToObj(i -> "{\"articuloId\": %d, \"cantidad\": 1}".formatted(maceta.getId()))
                .collect(Collectors.joining(","));
        vender(empleado, "{\"medioPago\": \"efectivo\", \"renglones\": [" + muchos + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Una venta admite hasta 100 renglones."));
        // un insumo no se vende, aunque la fábrica lo vea
        Articulo turba = datos.articulo(fabrica, TipoArticulo.insumo, "Turba", null);
        vender(duenoFabrica, renglones("efectivo", mayorista.getId(), turba, "1")).andExpect(status().isConflict());
        // la reventa de otro local no existe para este
        Articulo ajena = datos.articulo(norte, TipoArticulo.reventa, "Regadera", categoria);
        vender(empleado, renglones("efectivo", null, ajena, "1")).andExpect(status().isNotFound());
    }

    @Test
    void sinUbicacionSaleDePrincipalYSePuedeIndicarOtraDelLocal() throws Exception {
        UbicacionStock deposito = datos.ubicacion(centro, "Depósito");
        datos.stock(maceta, deposito, "3");
        abrirCaja(empleado);
        vender(empleado, """
                {"medioPago": "efectivo", "renglones": [
                    {"articuloId": %d, "cantidad": 2, "ubicacionId": %d}, {"articuloId": %d, "cantidad": 1}]}
                """.formatted(maceta.getId(), deposito.getId(), maceta.getId()))
                .andExpect(status().isCreated());
        assertThat(datos.cantidad(maceta, deposito)).isEqualByComparingTo("1");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("4");
        UbicacionStock ajena = datos.principal(norte);
        vender(empleado, """
                {"medioPago": "efectivo", "renglones": [{"articuloId": %d, "cantidad": 1, "ubicacionId": %d}]}
                """.formatted(maceta.getId(), ajena.getId())).andExpect(status().isNotFound());
    }

    // --- Fábrica ---

    @Test
    void laFabricaLeVendeAUnMayoristaConSuListaYSinCaja() throws Exception {
        String hoy = LocalDate.now(ZONA).toString();
        long id = id(vender(duenoFabrica, renglones("transferencia", mayorista.getId(), sustrato, "10"))
                .andExpect(status().isCreated())
                // 2000 + 21 % = 2420, por 10
                .andExpect(jsonPath("$.total").value(24200.00))
                .andExpect(jsonPath("$.fechaCobro").value(hoy))
                .andExpect(jsonPath("$.sesionCajaId").doesNotExist())
                .andExpect(jsonPath("$.cliente.nombre").value("Vivero Las Flores"))
                .andExpect(jsonPath("$.costoTotal").value(12000.00))
                // neto 20000: (20000 - 12000) / 20000
                .andExpect(jsonPath("$.margen").value(0.4)));
        assertThat(datos.cantidad(sustrato, datos.principal(fabrica))).isEqualByComparingTo("90");
        assertThat(jdbc.queryForObject("select count(*) from movimientos_caja where venta_id = ?", Integer.class, id))
                .isZero();

        String ayer = LocalDate.now(ZONA).minusDays(1).toString();
        vender(duenoFabrica, """
                {"medioPago": "efectivo", "clienteId": %d, "fechaCobro": "%s", "renglones": [{"articuloId": %d, "cantidad": 1}]}
                """.formatted(mayorista.getId(), ayer, sustrato.getId()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.fechaCobro").value(ayer));
        vender(duenoFabrica, """
                {"medioPago": "efectivo", "clienteId": %d, "fechaCobro": "%s", "renglones": [{"articuloId": %d, "cantidad": 1}]}
                """.formatted(mayorista.getId(), LocalDate.now(ZONA).plusDays(1), sustrato.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void laFabricaVendeSiempreAUnMayoristaConListaNuncaAUnLocal() throws Exception {
        vender(duenoFabrica, renglones("efectivo", null, sustrato, "1")).andExpect(status().isBadRequest());
        vender(duenoFabrica, renglones("efectivo", datos.clienteDelLocal(centro).getId(), sustrato, "1"))
                .andExpect(status().isConflict());
        Cliente sinLista = datos.cliente(fabrica, "Paisajista sin lista");
        vender(duenoFabrica, renglones("efectivo", sinLista.getId(), sustrato, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("El mayorista 'Paisajista sin lista' no tiene una lista de precios "
                        + "asignada: asignale la lista mayorista antes de venderle."));
        // un cliente de un local no existe para la fábrica
        vender(duenoFabrica, renglones("efectivo", datos.cliente(centro, "Ana").getId(), sustrato, "1"))
                .andExpect(status().isNotFound());
        // en un local no hay fecha de cobro: el cobro va a la caja
        abrirCaja(dueno);
        vender(dueno, """
                {"medioPago": "efectivo", "fechaCobro": "2026-01-01", "renglones": [{"articuloId": %d, "cantidad": 1}]}
                """.formatted(maceta.getId())).andExpect(status().isBadRequest());
    }

    // --- Anulación ---

    @Test
    void elDuenoAnulaUnaVentaVuelveElStockSeAnulaElComprobanteYSaleElDineroDeLaCaja() throws Exception {
        abrirCaja(empleado);
        long id = id(vender(empleado, renglones("efectivo", null, sustrato, "2", maceta, "1")));
        String ruta = "/api/ventas/" + id + "/anular";

        mvc.perform(json(post(ruta), empleado, "{\"motivo\": \"Se arrepintió\"}")).andExpect(status().isForbidden());
        mvc.perform(json(post(ruta), dueno, "{\"motivo\": \" \"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(ruta), dueno, "{\"motivo\": \"Se arrepintió\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("anulada"))
                .andExpect(jsonPath("$.anulacion.motivo").value("Se arrepintió"))
                .andExpect(jsonPath("$.comprobante.estado").value("anulado"));
        mvc.perform(json(post(ruta), dueno, "{\"motivo\": \"Otra vez\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("La venta ya está anulada."));

        assertThat(datos.cantidad(sustrato, principal)).isEqualByComparingTo("10");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForList("select cantidad from movimientos_stock where venta_id = ? and tipo = 'anulacion' order by cantidad",
                BigDecimal.class, id)).extracting(BigDecimal::intValue).containsExactly(1, 2);
        mvc.perform(get("/api/caja/actual").with(dueno))
                .andExpect(jsonPath("$.totales.ventasEfectivo").value(7500.00))
                .andExpect(jsonPath("$.totales.devolucionesEfectivo").value(7500.00))
                .andExpect(jsonPath("$.totales.efectivoEsperado").value(0));
    }

    @Test
    void sinCajaAbiertaNoSeAnulaNiSeDevuelveUnaVentaCobradaEnLaCaja() throws Exception {
        abrirCaja(empleado);
        long id = id(vender(empleado, renglones("efectivo", null, maceta, "2")));
        mvc.perform(json(post("/api/caja/cerrar"), empleado, "{\"efectivoContado\": 3000}")).andExpect(status().isOk());

        mvc.perform(json(post("/api/ventas/" + id + "/anular"), dueno, "{\"motivo\": \"Error\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("No hay una caja abierta en este local: abrila para devolver el dinero de la anulación."));
        mvc.perform(json(post("/api/ventas/" + id + "/devoluciones"), dueno, devolucion(id, 0, "1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("No hay una caja abierta en este local: abrila para devolver el dinero de la devolución."));

        // con la caja de otro turno abierta, el dinero sale de ella
        long otra = abrirCaja(empleado);
        mvc.perform(json(post("/api/ventas/" + id + "/anular"), dueno, "{\"motivo\": \"Error\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select sesion_caja_id from movimientos_caja where venta_id = ? and origen = 'devolucion'",
                Long.class, id)).isEqualTo(otra);
    }

    // --- Devolución parcial ---

    @Test
    void unaDevolucionParcialVuelveElStockEmiteUnaNotaDeCreditoYNoSePasaDeLoVendido() throws Exception {
        abrirCaja(empleado);
        long id = id(vender(empleado, renglones("efectivo", null, sustrato, "3", maceta, "2")));
        String ruta = "/api/ventas/" + id + "/devoluciones";

        mvc.perform(json(post(ruta), empleado, devolucion(id, 0, "1"))).andExpect(status().isForbidden());
        mvc.perform(json(post(ruta), dueno, devolucion(id, 0, "1"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("confirmada"))
                .andExpect(jsonPath("$.totalDevuelto").value(3000.00))
                .andExpect(jsonPath("$.renglones[0].cantidadDevuelta").value(1))
                .andExpect(jsonPath("$.devoluciones[0].motivo").value("Bolsa rota"))
                .andExpect(jsonPath("$.devoluciones[0].notaCredito.tipo").value("nota_credito"))
                .andExpect(jsonPath("$.devoluciones[0].notaCredito.numero").value("00001-00000001"))
                .andExpect(jsonPath("$.devoluciones[0].notaCredito.total").value(3000.00));
        assertThat(datos.cantidad(sustrato, principal)).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("""
                select nc.comprobante_asociado_id = v.comprobante_id from devoluciones_venta d
                join comprobantes nc on nc.id = d.nota_credito_id join ventas v on v.id = d.venta_id where v.id = ?
                """, Boolean.class, id)).isTrue();
        mvc.perform(get("/api/caja/actual").with(dueno))
                .andExpect(jsonPath("$.totales.devolucionesEfectivo").value(3000.00))
                .andExpect(jsonPath("$.totales.efectivoEsperado").value(9000.00));

        // quedan 2: no se devuelven 3
        mvc.perform(json(post(ruta), dueno, devolucion(id, 0, "3"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("De 'Sustrato 80 dm3' se vendieron 3 y ya se devolvieron 1: se pueden devolver hasta 2."));
        mvc.perform(json(post(ruta), dueno, devolucion(id, 0, "0.5"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(ruta), dueno, devolucion(id, 0, "2"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalDevuelto").value(9000.00));
        // una venta con devoluciones no se anula
        mvc.perform(json(post("/api/ventas/" + id + "/anular"), dueno, "{\"motivo\": \"x\"}"))
                .andExpect(status().isConflict());
        // un renglón de otra venta
        long otra = id(vender(empleado, renglones("efectivo", null, maceta, "1")));
        long renglonDeOtra = renglon(otra, 0);
        mvc.perform(json(post(ruta), dueno, """
                {"motivo": "x", "renglones": [{"detalleVentaId": %d, "cantidad": 1}]}
                """.formatted(renglonDeOtra))).andExpect(status().isBadRequest());
    }

    // --- Lectura y acceso ---

    @Test
    void elEmpleadoVeSoloLasVentasDelDiaYElDuenoTodas() throws Exception {
        abrirCaja(empleado);
        long vieja = id(vender(empleado, renglones("efectivo", null, maceta, "1")));
        long nueva = id(vender(empleado, renglones("efectivo", null, sustrato, "1")));
        em.flush();
        // la primera pasa a ser de hace tres días (con los triggers desactivados solo para el test)
        jdbc.execute("set local session_replication_role = replica");
        jdbc.update("update ventas set fecha_hora = now() - interval '3 days' where id = ?", vieja);
        jdbc.execute("set local session_replication_role = origin");
        em.clear();

        mvc.perform(get("/api/ventas").with(empleado)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.ventas[0].id").value(nueva));
        mvc.perform(get("/api/ventas/" + vieja).with(empleado)).andExpect(status().isNotFound());
        mvc.perform(get("/api/ventas").with(dueno)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/ventas").param("hasta", LocalDate.now(ZONA).minusDays(1).toString()).with(dueno))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.ventas[0].id").value(vieja));
        mvc.perform(get("/api/ventas").param("medio", "transferencia").with(dueno)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/ventas/" + vieja).with(dueno)).andExpect(status().isOk());
    }

    @Test
    void nadieVeNiAnulaNiDevuelveLasVentasDeOtroAmbito() throws Exception {
        abrirCaja(empleado);
        long id = id(vender(empleado, renglones("efectivo", null, maceta, "1")));
        RequestPostProcessor duenoNorte = datos.en(norte, Perfil.dueno_local);

        mvc.perform(get("/api/ventas/" + id).with(duenoNorte)).andExpect(status().isNotFound());
        mvc.perform(get("/api/ventas/" + id).with(duenoFabrica)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/ventas/" + id + "/anular"), duenoNorte, "{\"motivo\": \"x\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/ventas/" + id + "/devoluciones"), duenoFabrica, devolucion(id, 0, "1")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ventas").with(duenoNorte)).andExpect(jsonPath("$.total").value(0));
        // la fábrica ve solo sus ventas
        vender(duenoFabrica, renglones("efectivo", mayorista.getId(), sustrato, "1")).andExpect(status().isCreated());
        mvc.perform(get("/api/ventas").with(duenoFabrica)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.ventas[0].cliente.nombre").value("Vivero Las Flores"));
    }

    @Test
    void elAdministradorTecnicoRecibe403YSinSesion401() throws Exception {
        RequestPostProcessor admin = datos.admin();
        vender(admin, renglones("efectivo", null, maceta, "1")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ventas").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/ventas/1").with(admin)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/ventas/1/anular"), admin, "{\"motivo\": \"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ventas")).andExpect(status().isUnauthorized());
    }

    @Test
    void elHistorialDelClienteTieneSusVentasConLosPermisosDelCliente() throws Exception {
        Cliente ana = datos.cliente(centro, "Ana");
        abrirCaja(empleado);
        long primera = id(vender(empleado, renglones("efectivo", ana.getId(), maceta, "1")));
        long segunda = id(vender(empleado, renglones("efectivo", ana.getId(), sustrato, "1")));
        vender(empleado, renglones("efectivo", null, maceta, "1")).andExpect(status().isCreated());
        mvc.perform(json(post("/api/ventas/" + primera + "/devoluciones"), dueno, devolucion(primera, 0, "1")))
                .andExpect(status().isCreated());

        String ruta = "/api/clientes/" + ana.getId() + "/historial";
        String delEmpleado = mvc.perform(get(ruta).with(empleado)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.ventas[0].id").value(segunda))
                .andExpect(jsonPath("$.ventas[1].totalDevuelto").value(1500.00))
                .andReturn().getResponse().getContentAsString();
        assertThat(delEmpleado.toLowerCase()).doesNotContain("costo", "margen");
        mvc.perform(get(ruta).with(dueno)).andExpect(status().isOk());
        mvc.perform(get(ruta).with(datos.en(norte, Perfil.dueno_local))).andExpect(status().isNotFound());
        mvc.perform(get(ruta).with(datos.admin())).andExpect(status().isForbidden());
    }

    @Test
    void laVentaLaAnulacionYLaDevolucionQuedanEnAuditoriaConElAmbitoAfectado() throws Exception {
        abrirCaja(empleado);
        long id = id(vender(empleado, renglones("efectivo", null, sustrato, "2")));
        mvc.perform(json(post("/api/ventas/" + id + "/devoluciones"), dueno, devolucion(id, 0, "1")))
                .andExpect(status().isCreated());
        long otra = id(vender(empleado, renglones("efectivo", null, maceta, "1")));
        mvc.perform(json(post("/api/ventas/" + otra + "/anular"), dueno, "{\"motivo\": \"Error de carga\"}"))
                .andExpect(status().isOk());

        List<EventoAuditoria> registros = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.entidad().equals("venta")).toList();
        assertThat(registros).extracting(EventoAuditoria::accion)
                .containsExactly("registrar", "devolver", "registrar", "anular");
        assertThat(registros).allSatisfy(e -> assertThat(e.ambitosAfectados()).containsExactly(centro.getId()));
        assertThat(registros.get(0).detalle().get("total")).isEqualTo(new BigDecimal("6000.00"));
        assertThat(registros.get(1).detalle().get("motivo")).isEqualTo("Bolsa rota");
        assertThat(registros.get(3).detalle().get("motivo")).isEqualTo("Error de carga");
    }

    @Test
    void unaVentaDeDiezRenglonesSeRegistraEnMenosDeDosSegundos() throws Exception {
        List<Articulo> diez = IntStream.range(0, 10).mapToObj(i -> {
            Articulo a = datos.articulo(centro, TipoArticulo.reventa, "Artículo " + i, categoria);
            datos.precio(listaCentro, a, "100");
            datos.stock(a, principal, "100");
            return a;
        }).toList();
        abrirCaja(empleado);
        String cuerpo = "{\"medioPago\": \"efectivo\", \"renglones\": [" + diez.stream()
                .map(a -> "{\"articuloId\": %d, \"cantidad\": 2}".formatted(a.getId())).collect(Collectors.joining(","))
                + "]}";
        vender(empleado, cuerpo).andExpect(status().isCreated()); // la primera calienta la aplicación

        long inicio = System.nanoTime();
        vender(empleado, cuerpo).andExpect(status().isCreated()).andExpect(jsonPath("$.renglones.length()").value(10));
        long milisegundos = (System.nanoTime() - inicio) / 1_000_000;
        assertThat(milisegundos).as("una venta de 10 renglones tardó %d ms", milisegundos).isLessThan(2000);
    }

    // --- Ayudas ---

    private long abrirCaja(RequestPostProcessor sesion) throws Exception {
        return id(mvc.perform(json(post("/api/caja/abrir"), sesion, "{\"saldoInicial\": 0}"))
                .andExpect(status().isCreated()));
    }

    private ResultActions vender(RequestPostProcessor sesion, String cuerpo) throws Exception {
        return mvc.perform(json(post("/api/ventas"), sesion, cuerpo));
    }

    /** Una venta con el medio, el cliente y pares de artículo y cantidad. */
    private static String renglones(String medio, Long clienteId, Object... articulosYCantidades) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < articulosYCantidades.length; i += 2) {
            r.append(i == 0 ? "" : ",").append("{\"articuloId\": %d, \"cantidad\": %s}"
                    .formatted(((Articulo) articulosYCantidades[i]).getId(), articulosYCantidades[i + 1]));
        }
        return "{\"medioPago\": \"%s\", \"clienteId\": %s, \"renglones\": [%s]}".formatted(medio, clienteId, r);
    }

    private String conDescuento(String porcentaje) {
        return "{\"medioPago\": \"efectivo\", \"porcentajeDescuento\": %s, \"renglones\": [{\"articuloId\": %d, \"cantidad\": 1}]}"
                .formatted(porcentaje, maceta.getId());
    }

    /** Devolución del renglón número {@code indice} de la venta, con motivo "Bolsa rota". */
    private String devolucion(long venta, int indice, String cantidad) {
        return "{\"motivo\": \"Bolsa rota\", \"renglones\": [{\"detalleVentaId\": %d, \"cantidad\": %s}]}"
                .formatted(renglon(venta, indice), cantidad);
    }

    private long renglon(long venta, int indice) {
        em.flush();
        return jdbc.queryForList("select id from detalle_ventas where venta_id = ? order by id", Long.class, venta)
                .get(indice);
    }

    private long id(ResultActions r) throws Exception {
        JsonNode n = mapper.readTree(r.andReturn().getResponse().getContentAsString());
        assertThat(n.has("id")).as(n.toString()).isTrue();
        return n.get("id").asLong();
    }
}
