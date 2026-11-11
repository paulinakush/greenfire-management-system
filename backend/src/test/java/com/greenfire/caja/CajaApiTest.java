package com.greenfire.caja;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.EntityManager;

/**
 * Caja por la API: apertura, movimientos, cierre a ciegas, corrección, puntos de enganche para ventas y cobranzas,
 * y qué puede hacer y ver cada perfil en cada ámbito.
 */
@PruebaIntegracion
class CajaApiTest {

    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    CajaService caja;
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
    RequestPostProcessor dueno;
    RequestPostProcessor empleado;
    Long usuario;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        dueno = datos.en(centro, Perfil.dueno_local);
        empleado = datos.en(centro, Perfil.empleado_local);
        usuario = datos.usuario("Caja").getId();
    }

    // --- Recorrido completo ---

    @Test
    void elEmpleadoAbreRegistraUnGastoYCierraACiegas() throws Exception {
        long id = abrir(empleado, "1000");
        // ventas y cobranzas, por los puntos de enganche (los módulos 5 y 7 los van a usar así)
        cobro(OrigenMovimientoCaja.venta, MedioPago.efectivo, "500");
        cobro(OrigenMovimientoCaja.venta, MedioPago.transferencia, "300");
        cobro(OrigenMovimientoCaja.venta, MedioPago.billetera_virtual, "80");
        cobro(OrigenMovimientoCaja.cobranza, MedioPago.efectivo, "100");
        cobro(OrigenMovimientoCaja.devolucion, MedioPago.efectivo, "50");
        movimiento(empleado, "{\"rubro\":\"gasto\",\"medioPago\":\"efectivo\",\"monto\":200,\"observacion\":\"Limpieza\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("egreso"));

        // a ciegas: lo que ve el empleado de la caja abierta no trae totales ni el esperado
        String abierta = mvc.perform(get("/api/caja/actual").with(empleado)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.saldoInicial").value(1000.00))
                .andExpect(jsonPath("$.movimientos.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(abierta.toLowerCase()).doesNotContain("esperado", "totales", "total", "diferencia", "olvidada");
        assertThat(mapper.readTree(abierta).get("cierre").isNull()).isTrue();

        // esperado = 1000 + 500 (ventas) + 100 (cobranzas) - 200 (gasto) - 50 (devolución) = 1350
        mvc.perform(json(post("/api/caja/cerrar"), empleado, "{\"efectivoContado\": 1340, \"observaciones\": \"Faltan 10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cerrada"))
                .andExpect(jsonPath("$.cierre.totalEfectivo").value(550.00))
                .andExpect(jsonPath("$.cierre.totalTransferencia").value(300.00))
                .andExpect(jsonPath("$.cierre.totalBilletera").value(80.00))
                .andExpect(jsonPath("$.cierre.totalGeneral").value(930.00))
                .andExpect(jsonPath("$.cierre.ingresosEfectivo").value(0))
                .andExpect(jsonPath("$.cierre.egresosEfectivo").value(200.00))
                .andExpect(jsonPath("$.cierre.efectivoEsperado").value(1350.00))
                .andExpect(jsonPath("$.cierre.efectivoContado").value(1340.00))
                .andExpect(jsonPath("$.cierre.diferencia").value(-10.00));

        Map<String, Object> fila = jdbc.queryForMap("select * from sesiones_caja where id = ?", id);
        assertThat(fila).containsEntry("estado", "cerrada");
        assertThat((BigDecimal) fila.get("efectivo_esperado")).isEqualByComparingTo("1350");
        assertThat((BigDecimal) fila.get("diferencia")).isEqualByComparingTo("-10");
        mvc.perform(get("/api/caja/actual").with(empleado)).andExpect(status().isNoContent());
    }

    @Test
    void elDuenoVeTodoDeLaCajaAbiertaConElDesglose() throws Exception {
        abrir(dueno, "100");
        cobro(OrigenMovimientoCaja.venta, MedioPago.efectivo, "1000");
        cobro(OrigenMovimientoCaja.devolucion, MedioPago.transferencia, "40");
        movimiento(dueno, "{\"rubro\":\"retiro\",\"medioPago\":\"efectivo\",\"monto\":300,\"observacion\":\"Retiro\"}")
                .andExpect(status().isCreated());
        movimiento(dueno, "{\"rubro\":\"aporte\",\"medioPago\":\"efectivo\",\"monto\":50,\"observacion\":\"Cambio\"}")
                .andExpect(status().isCreated());

        mvc.perform(get("/api/caja/actual").with(dueno)).andExpect(status().isOk())
                .andExpect(jsonPath("$.olvidada").value(false))
                .andExpect(jsonPath("$.totales.ventasEfectivo").value(1000.00))
                .andExpect(jsonPath("$.totales.totalTransferencia").value(-40.00))
                .andExpect(jsonPath("$.totales.ingresosEfectivo").value(50.00))
                .andExpect(jsonPath("$.totales.egresosEfectivo").value(300.00))
                .andExpect(jsonPath("$.totales.efectivoEsperado").value(850.00))
                .andExpect(jsonPath("$.movimientos.length()").value(4));
    }

    // --- Reglas ---

    @Test
    void unaSolaCajaAbiertaPorLocalYSinCajaNoHayMovimientosNiCierre() throws Exception {
        movimiento(empleado, "{\"rubro\":\"gasto\",\"medioPago\":\"efectivo\",\"monto\":1,\"observacion\":\"x\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.mensaje").value("No hay una caja abierta en este local."));
        mvc.perform(json(post("/api/caja/cerrar"), empleado, "{\"efectivoContado\": 0}")).andExpect(status().isConflict());
        mvc.perform(get("/api/caja/actual").with(dueno)).andExpect(status().isNoContent());

        abrir(empleado, "0");
        mvc.perform(json(post("/api/caja/abrir"), dueno, "{\"saldoInicial\": 10}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Ya hay una caja abierta en este local."));
        // otro local abre la suya
        abrir(datos.en(norte, Perfil.empleado_local), "0");
    }

    @Test
    void losMovimientosManualesValidanRubroTipoMedioYObservacion() throws Exception {
        abrir(dueno, "0");

        movimiento(dueno, "{\"rubro\":\"otro\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"x\"}")
                .andExpect(status().isBadRequest());
        movimiento(dueno, "{\"rubro\":\"otro\",\"tipo\":\"ingreso\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"Sobrante\"}")
                .andExpect(status().isCreated());
        movimiento(dueno, "{\"rubro\":\"gasto\",\"tipo\":\"ingreso\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"x\"}")
                .andExpect(status().isBadRequest());
        movimiento(dueno, "{\"rubro\":\"gasto\",\"medioPago\":\"cuenta_corriente\",\"monto\":5,\"observacion\":\"x\"}")
                .andExpect(status().isBadRequest());
        movimiento(dueno, "{\"rubro\":\"gasto\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\" \"}")
                .andExpect(status().isBadRequest());
        movimiento(dueno, "{\"rubro\":\"gasto\",\"medioPago\":\"efectivo\",\"monto\":0,\"observacion\":\"x\"}")
                .andExpect(status().isBadRequest());
        movimiento(dueno, "{\"rubro\":\"prestamo\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"x\"}")
                .andExpect(status().isBadRequest());
        movimiento(dueno, "{\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"x\"}")
                .andExpect(status().isBadRequest());
        movimiento(dueno, "{\"rubro\":\"pago_fabrica\",\"medioPago\":\"transferencia\",\"monto\":5,\"observacion\":\"Pago\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.tipo").value("egreso"));
    }

    @Test
    void unErrorSeCorrigeConUnMovimientoContrario() throws Exception {
        abrir(empleado, "500");
        long gasto = id(movimiento(empleado,
                "{\"rubro\":\"gasto\",\"medioPago\":\"efectivo\",\"monto\":200,\"observacion\":\"Era 20\"}"));

        long correccion = id(movimiento(empleado, "{\"corrigeMovimientoId\": %d, \"monto\": 180, \"observacion\": \"Se cargó 200 en vez de 20\"}"
                .formatted(gasto)).andExpect(jsonPath("$.tipo").value("ingreso"))
                .andExpect(jsonPath("$.rubro").value("gasto"))
                .andExpect(jsonPath("$.medioPago").value("efectivo"))
                .andExpect(jsonPath("$.corrigeMovimientoId").value(gasto)));

        movimiento(empleado, "{\"corrigeMovimientoId\": %d, \"monto\": 30, \"observacion\": \"x\"}".formatted(gasto))
                .andExpect(status().isConflict());
        movimiento(empleado, "{\"corrigeMovimientoId\": %d, \"monto\": 1, \"observacion\": \"x\"}".formatted(correccion))
                .andExpect(status().isConflict());
        movimiento(empleado, "{\"corrigeMovimientoId\": %d, \"tipo\": \"egreso\", \"monto\": 1, \"observacion\": \"x\"}"
                .formatted(gasto)).andExpect(status().isBadRequest());
        movimiento(empleado, "{\"corrigeMovimientoId\": 999999, \"monto\": 1, \"observacion\": \"x\"}")
                .andExpect(status().isNotFound());

        // el gasto quedó en 20: esperado 500 - 200 + 180
        mvc.perform(get("/api/caja/actual").with(dueno))
                .andExpect(jsonPath("$.totales.efectivoEsperado").value(480.00));
    }

    @Test
    void laCorreccionDelCierreEsDelDuenoConMotivoYQuedaEnAuditoria() throws Exception {
        long id = abrir(empleado, "100");
        mvc.perform(json(post("/api/caja/cerrar"), empleado, "{\"efectivoContado\": 90}")).andExpect(status().isOk());
        String ruta = "/api/caja/sesiones/" + id + "/correccion";

        mvc.perform(json(post(ruta), empleado, "{\"efectivoContado\": 100, \"motivo\": \"Recontado\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(ruta), dueno, "{\"efectivoContado\": 100, \"motivo\": \" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post(ruta), dueno, "{\"efectivoContado\": 90, \"motivo\": \"Igual\"}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post(ruta), dueno, "{\"efectivoContado\": 100, \"motivo\": \"Había un billete en el cajón\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.efectivoContado").value(100.00))
                .andExpect(jsonPath("$.diferencia").value(0))
                .andExpect(jsonPath("$.correccion.motivo").value("Había un billete en el cajón"))
                .andExpect(jsonPath("$.totales.efectivoEsperado").value(100.00));

        EventoAuditoria correccion = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals("corregir_cierre")).findFirst().orElseThrow();
        assertThat(correccion.ambitosAfectados()).containsExactly(centro.getId());
        assertThat(correccion.detalle().get("efectivoContado"))
                .isEqualTo(Map.of("antes", new BigDecimal("90.00"), "despues", new BigDecimal("100.00")));
        assertThat(correccion.detalle().get("diferencia"))
                .isEqualTo(Map.of("antes", new BigDecimal("-10.00"), "despues", new BigDecimal("0.00")));
        assertThat(correccion.detalle().get("motivo")).isEqualTo("Había un billete en el cajón");

        // una caja abierta no se corrige
        long abierta = abrir(empleado, "0");
        mvc.perform(json(post("/api/caja/sesiones/" + abierta + "/correccion"), dueno,
                "{\"efectivoContado\": 1, \"motivo\": \"x\"}")).andExpect(status().isConflict());
    }

    @Test
    void laAperturaCadaMovimientoElCierreYLaCorreccionQuedanEnAuditoria() throws Exception {
        long id = abrir(empleado, "10");
        movimiento(empleado, "{\"rubro\":\"gasto\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"Café\"}")
                .andExpect(status().isCreated());
        cobro(OrigenMovimientoCaja.venta, MedioPago.efectivo, "20");
        mvc.perform(json(post("/api/caja/cerrar"), empleado, "{\"efectivoContado\": 25}")).andExpect(status().isOk());
        mvc.perform(json(post("/api/caja/sesiones/" + id + "/correccion"), dueno,
                "{\"efectivoContado\": 24, \"motivo\": \"Recuento\"}")).andExpect(status().isOk());

        List<EventoAuditoria> registros = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.entidad().equals("sesion_caja") || e.entidad().equals("movimiento_caja")).toList();
        assertThat(registros).extracting(EventoAuditoria::accion)
                .containsExactly("abrir", "registrar_movimiento", "registrar_movimiento", "cerrar", "corregir_cierre");
        assertThat(registros).allSatisfy(e -> assertThat(e.ambitosAfectados()).containsExactly(centro.getId()));
        assertThat(registros.get(3).detalle().get("efectivoEsperado")).isEqualTo(new BigDecimal("25.00"));
    }

    // --- Puntos de enganche ---

    @Test
    void losPuntosDeEngancheExigenUnaCajaAbiertaYNoAceptanCuentaCorriente() throws Exception {
        assertThatThrownBy(() -> caja.exigirAbierta(centro.getId())).isInstanceOf(ReglaNegocioException.class)
                .hasMessage("No hay una caja abierta en este local.");
        abrir(empleado, "0");
        SesionCaja abierta = caja.exigirAbierta(centro.getId());

        assertThatThrownBy(() -> caja.registrarCobro(abierta, OrigenMovimientoCaja.venta, MedioPago.cuenta_corriente,
                BigDecimal.TEN, usuario, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> caja.registrarCobro(abierta, OrigenMovimientoCaja.manual, MedioPago.efectivo,
                BigDecimal.TEN, usuario, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> caja.registrarCobro(abierta, OrigenMovimientoCaja.venta, MedioPago.efectivo,
                BigDecimal.ZERO, usuario, null)).isInstanceOf(IllegalArgumentException.class);

        MovimientoCaja venta = caja.registrarCobro(abierta, OrigenMovimientoCaja.venta, MedioPago.efectivo,
                new BigDecimal("12.5"), usuario, "Venta 1");
        MovimientoCaja devolucion = caja.registrarCobro(abierta, OrigenMovimientoCaja.devolucion,
                MedioPago.efectivo, BigDecimal.ONE, usuario, null);
        assertThat(venta.getTipo()).isEqualTo(TipoMovimientoCaja.ingreso);
        assertThat(venta.getMonto()).isEqualByComparingTo("12.50");
        assertThat(devolucion.getTipo()).isEqualTo(TipoMovimientoCaja.egreso);
        assertThat(venta.getRubro()).isNull();
        // el empleado no ve en su lista los movimientos de ventas (los ve en las ventas)
        mvc.perform(get("/api/caja/actual").with(empleado)).andExpect(jsonPath("$.movimientos.length()").value(0));
        // el local de otro ámbito no tiene caja abierta
        assertThatThrownBy(() -> caja.exigirAbierta(norte.getId())).isInstanceOf(ReglaNegocioException.class);
    }

    // --- Acceso por perfil y por ámbito ---

    @Test
    void elEmpleadoNoRegistraRetirosNiPagosALaFabrica() throws Exception {
        abrir(empleado, "0");
        movimiento(empleado, "{\"rubro\":\"retiro\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"x\"}")
                .andExpect(status().isForbidden());
        movimiento(empleado, "{\"rubro\":\"pago_fabrica\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"x\"}")
                .andExpect(status().isForbidden());
        movimiento(empleado, "{\"rubro\":\"aporte\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"Cambio\"}")
                .andExpect(status().isCreated());
        // tampoco corrige un retiro del dueño, ni lo ve
        long retiro = id(movimiento(dueno,
                "{\"rubro\":\"pago_fabrica\",\"medioPago\":\"efectivo\",\"monto\":5,\"observacion\":\"Pago\"}"));
        movimiento(empleado, "{\"corrigeMovimientoId\": %d, \"monto\": 5, \"observacion\": \"x\"}".formatted(retiro))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/caja/actual").with(empleado)).andExpect(jsonPath("$.movimientos.length()").value(1));
    }

    @Test
    void laFabricaNoTieneCajaSalvoQueSuDuenoEntreAlLocalCentral() throws Exception {
        Ambito central = datos.local("Local La Rioja");
        Usuario duenoDeLosDos = datos.usuario("Dueña");
        datos.perfil(duenoDeLosDos, fabrica, Perfil.dueno_fabrica);
        datos.perfil(duenoDeLosDos, central, Perfil.dueno_local);
        RequestPostProcessor enFabrica = datos.sesion(Sesion.enAmbito(duenoDeLosDos.getId(), fabrica.getId(),
                Perfil.dueno_fabrica));
        RequestPostProcessor enCentral = datos.sesion(Sesion.enAmbito(duenoDeLosDos.getId(), central.getId(),
                Perfil.dueno_local));
        long deCentro = abrir(empleado, "0");

        mvc.perform(json(post("/api/caja/abrir"), enFabrica, "{\"saldoInicial\": 0}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/caja/actual").with(enFabrica)).andExpect(status().isForbidden());
        mvc.perform(get("/api/caja/sesiones").with(enFabrica)).andExpect(status().isForbidden());
        mvc.perform(get("/api/caja/sesiones/" + deCentro).with(enFabrica)).andExpect(status().isForbidden());

        long deCentral = abrir(enCentral, "10");
        mvc.perform(get("/api/caja/sesiones/" + deCentral).with(enCentral)).andExpect(status().isOk());
        // desde el central no ve la caja de otro local
        mvc.perform(get("/api/caja/sesiones/" + deCentro).with(enCentral)).andExpect(status().isNotFound());
    }

    @Test
    void elAdministradorTecnicoRecibe403YSinSesion401() throws Exception {
        RequestPostProcessor admin = datos.admin();
        mvc.perform(json(post("/api/caja/abrir"), admin, "{\"saldoInicial\": 0}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/caja/actual").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/caja/sesiones").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/caja/actual")).andExpect(status().isUnauthorized());
    }

    @Test
    void nadieVeNiCorrigeLaCajaDeOtroLocal() throws Exception {
        long id = abrir(empleado, "10");
        mvc.perform(json(post("/api/caja/cerrar"), empleado, "{\"efectivoContado\": 10}")).andExpect(status().isOk());
        RequestPostProcessor duenoNorte = datos.en(norte, Perfil.dueno_local);

        mvc.perform(get("/api/caja/sesiones/" + id).with(duenoNorte)).andExpect(status().isNotFound());
        mvc.perform(get("/api/caja/sesiones/" + id).with(datos.en(norte, Perfil.empleado_local)))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/caja/sesiones/" + id + "/correccion"), duenoNorte,
                "{\"efectivoContado\": 1, \"motivo\": \"x\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/caja/sesiones").with(duenoNorte)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void elEmpleadoVeLaAbiertaYLasDelDiaYElDuenoElHistorial() throws Exception {
        long vieja = sesionCerradaHaceDias(3);
        long deHoy = abrir(empleado, "10");
        mvc.perform(json(post("/api/caja/cerrar"), empleado, "{\"efectivoContado\": 10}")).andExpect(status().isOk());
        long abierta = abrir(empleado, "20");

        mvc.perform(get("/api/caja/sesiones").with(empleado)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.sesiones[0].id").value(abierta))
                .andExpect(jsonPath("$.sesiones[0].cierre").doesNotExist())
                .andExpect(jsonPath("$.sesiones[1].id").value(deHoy))
                .andExpect(jsonPath("$.sesiones[1].cierre.diferencia").value(0));
        mvc.perform(get("/api/caja/sesiones/" + vieja).with(empleado)).andExpect(status().isNotFound());

        mvc.perform(get("/api/caja/sesiones").with(dueno)).andExpect(jsonPath("$.total").value(3));
        String hoy = LocalDate.now(ZONA).toString();
        mvc.perform(get("/api/caja/sesiones").param("desde", hoy).with(dueno)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/caja/sesiones").param("hasta", LocalDate.now(ZONA).minusDays(1).toString()).with(dueno))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.sesiones[0].id").value(vieja));
        mvc.perform(get("/api/caja/sesiones/" + vieja).with(dueno)).andExpect(status().isOk())
                .andExpect(jsonPath("$.efectivoContado").value(100.00));
    }

    @Test
    void unaCajaAbiertaHaceMasDe24HorasSeMarcaOlvidadaParaElDuenoSinCerrarse() throws Exception {
        em.flush();
        long id = jdbc.queryForObject("""
                insert into sesiones_caja (ambito_id, usuario_apertura_id, abierta_at, saldo_inicial)
                values (?, ?, now() - interval '25 hours', 0) returning id
                """, Long.class, centro.getId(), usuario);

        mvc.perform(get("/api/caja/actual").with(dueno)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.olvidada").value(true))
                .andExpect(jsonPath("$.estado").value("abierta"));
        mvc.perform(get("/api/caja/sesiones").with(dueno)).andExpect(jsonPath("$.sesiones[0].olvidada").value(true));
        // el empleado la ve (para cerrarla), sin la marca
        mvc.perform(get("/api/caja/actual").with(empleado)).andExpect(status().isOk())
                .andExpect(jsonPath("$.olvidada").doesNotExist());
        // no se puede abrir otra hasta cerrarla
        mvc.perform(json(post("/api/caja/abrir"), empleado, "{\"saldoInicial\": 0}")).andExpect(status().isConflict());
    }

    // --- Ayudas ---

    private long abrir(RequestPostProcessor sesion, String saldo) throws Exception {
        return id(mvc.perform(json(post("/api/caja/abrir"), sesion, "{\"saldoInicial\": " + saldo + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("abierta")));
    }

    private org.springframework.test.web.servlet.ResultActions movimiento(RequestPostProcessor sesion, String cuerpo)
            throws Exception {
        return mvc.perform(json(post("/api/caja/movimientos"), sesion, cuerpo));
    }

    private long id(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        JsonNode n = mapper.readTree(r.andReturn().getResponse().getContentAsString());
        assertThat(n.has("id")).as(n.toString()).isTrue();
        return n.get("id").asLong();
    }

    private void cobro(OrigenMovimientoCaja origen, MedioPago medio, String monto) {
        caja.registrarCobro(caja.exigirAbierta(centro.getId()), origen, medio, new BigDecimal(monto), usuario, null);
    }

    /** Una sesión de Centro abierta y cerrada hace días, con 100 de saldo y lo mismo contado. */
    private long sesionCerradaHaceDias(int dias) {
        em.flush();
        long id = jdbc.queryForObject("""
                insert into sesiones_caja (ambito_id, usuario_apertura_id, abierta_at, saldo_inicial)
                values (?, ?, now() - make_interval(days => ?), 100) returning id
                """, Long.class, centro.getId(), usuario, dias);
        jdbc.update("""
                update sesiones_caja set estado = 'cerrada', usuario_cierre_id = usuario_apertura_id,
                    cerrada_at = abierta_at + interval '8 hours', total_efectivo = 0, total_transferencia = 0,
                    total_billetera = 0, total_general = 0, ingresos_efectivo = 0, egresos_efectivo = 0,
                    efectivo_esperado = 100, efectivo_contado = 100, diferencia = 0
                where id = ?
                """, id);
        return id;
    }
}
