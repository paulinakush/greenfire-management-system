package com.greenfire.clientes;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.seguridad.Perfil;
import com.jayway.jsonpath.JsonPath;

@PruebaIntegracion
class ClienteApiTest {

    /** CUIT de prueba, con dígito verificador válido. */
    static final String CUIT = "20123456786";
    static final String OTRO_CUIT = "27234567891";

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    RequestPostProcessor duenoCentro;
    RequestPostProcessor empleadoCentro;
    RequestPostProcessor duenoFabrica;

    @BeforeEach
    void ambitos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        duenoCentro = datos.en(centro, Perfil.dueno_local);
        empleadoCentro = datos.en(centro, Perfil.empleado_local);
        duenoFabrica = datos.en(fabrica, Perfil.dueno_fabrica);
    }

    // --- Qué ve cada ámbito ---

    @Test
    void cadaLocalVeSoloSusClientes() throws Exception {
        Cliente ana = datos.cliente(centro, "Ana");
        Cliente beto = datos.cliente(norte, "Beto");

        mvc.perform(get("/api/clientes").with(duenoCentro))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientes[*].nombre").value(containsInAnyOrder("Ana")))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/clientes/" + ana.getId()).with(duenoCentro)).andExpect(status().isOk());
        mvc.perform(get("/api/clientes/" + beto.getId()).with(duenoCentro)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/clientes/" + beto.getId()), duenoCentro, datosCliente("Beto", null)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/clientes/" + beto.getId() + "/desactivar").with(duenoCentro))
                .andExpect(status().isNotFound());
    }

    @Test
    void elAdministradorTecnicoNoVeClientes() throws Exception {
        datos.cliente(centro, "Ana");

        mvc.perform(get("/api/clientes").with(datos.admin())).andExpect(status().isForbidden());
    }

    @Test
    void buscaPorNombreRazonSocialODocumentoYPagina() throws Exception {
        crear(duenoCentro, """
                {"datos": {"nombre": "Ana Pérez", "documentoTipo": "cuit", "documentoNumero": "%s"}}""".formatted(CUIT));
        crear(duenoCentro, """
                {"datos": {"razonSocial": "Vivero Las Flores SRL"}}""");
        datos.cliente(centro, "Carlos");

        mvc.perform(get("/api/clientes").param("q", "pérez").with(duenoCentro))
                .andExpect(jsonPath("$.clientes[*].nombre").value(containsInAnyOrder("Ana Pérez")));
        mvc.perform(get("/api/clientes").param("q", "flores").with(duenoCentro))
                .andExpect(jsonPath("$.clientes[0].razonSocial").value("Vivero Las Flores SRL"));
        mvc.perform(get("/api/clientes").param("q", CUIT.substring(2, 8)).with(duenoCentro))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/clientes").param("tamano", "2").param("pagina", "1").with(duenoCentro))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.clientes.length()").value(1))
                .andExpect(jsonPath("$.clientes[0].razonSocial").value("Vivero Las Flores SRL"));
    }

    // --- Dueño de local ---

    @Test
    void elDuenoDeLocalCreaUnClienteConCuentaCorrienteYQuedaEnAuditoria() throws Exception {
        ListaPrecios mayorista = datos.lista(centro, "Mayorista", true, false);

        String respuesta = crear(duenoCentro, """
                {"datos": {"nombre": "  Ana  ", "documentoTipo": "cuit", "documentoNumero": "%s",
                           "condicionIva": "monotributo", "email": "ana@greenfire.test"},
                 "condiciones": {"cuentaCorrienteHabilitada": true, "limiteCredito": 50000,
                                 "listaPreciosId": %d, "plazoPagoDias": 30}}
                """.formatted(CUIT, mayorista.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Ana"))
                .andExpect(jsonPath("$.condicionIva").value("monotributo"))
                .andExpect(jsonPath("$.cuentaCorrienteHabilitada").value(true))
                .andExpect(jsonPath("$.limiteCredito").value(50000))
                .andExpect(jsonPath("$.listaPreciosNombre").value("Mayorista"))
                .andExpect(jsonPath("$.plazoPagoDias").value(30))
                .andExpect(jsonPath("$.localId").isEmpty())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(respuesta, "$.id");

        assertThat(eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("crear")))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.entidad()).isEqualTo("cliente");
                    assertThat(e.entidadId()).isEqualTo(id.longValue());
                    assertThat(e.ambitosAfectados()).containsExactly(centro.getId());
                    assertThat(e.detalle().get("limiteCredito")).isNotNull();
                });
    }

    @Test
    void sinCondicionesElClienteEsConsumidorFinalSinCuentaCorriente() throws Exception {
        crear(duenoCentro, "{\"datos\": {\"nombre\": \"Ana\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.condicionIva").value("consumidor_final"))
                .andExpect(jsonPath("$.cuentaCorrienteHabilitada").value(false))
                .andExpect(jsonPath("$.limiteCredito").isEmpty());
    }

    @Test
    void laEdicionGuardaEnAuditoriaSoloLoQueCambio() throws Exception {
        Cliente ana = datos.cliente(centro, "Ana");

        mvc.perform(json(put("/api/clientes/" + ana.getId()), duenoCentro, """
                        {"nombre": "Ana", "telefono": "351 555-0000"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telefono").value("351 555-0000"));

        assertThat(eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("editar")))
                .singleElement()
                .satisfies(e -> assertThat(e.detalle()).containsOnlyKeys("telefono"));
    }

    @Test
    void laListaDePreciosTieneQueSerDelLocalYEstarActiva() throws Exception {
        Cliente ana = datos.cliente(centro, "Ana");
        ListaPrecios deNorte = datos.lista(norte, "Mayorista", true, false);
        ListaPrecios inactiva = datos.lista(centro, "Vieja", true, false);
        inactiva.setActivo(false);

        mvc.perform(json(put("/api/clientes/" + ana.getId() + "/condiciones"), duenoCentro,
                        condiciones(false, null, deNorte.getId(), null)))
                .andExpect(status().isNotFound());
        mvc.perform(json(put("/api/clientes/" + ana.getId() + "/condiciones"), duenoCentro,
                        condiciones(false, null, inactiva.getId(), null)))
                .andExpect(status().isConflict());
    }

    @Test
    void elDuenoDesactivaYReactivaUnCliente() throws Exception {
        Cliente ana = datos.cliente(centro, "Ana");

        mvc.perform(post("/api/clientes/" + ana.getId() + "/desactivar").with(duenoCentro))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
        mvc.perform(get("/api/clientes").param("activo", "false").with(duenoCentro))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(post("/api/clientes/" + ana.getId() + "/activar").with(duenoCentro))
                .andExpect(jsonPath("$.activo").value(true));
        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .contains("desactivar", "activar");
    }

    // --- Empleado ---

    @Test
    void elEmpleadoVeLosClientesActivosSinCondicionesComerciales() throws Exception {
        Cliente ana = datos.cliente(centro, "Ana");
        ana.setLimiteCredito(new BigDecimal("1000"));
        Cliente inactivo = datos.cliente(centro, "Inactivo");
        inactivo.setActivo(false);

        mvc.perform(get("/api/clientes").param("activo", "false").with(empleadoCentro))
                .andExpect(jsonPath("$.clientes[*].nombre").value(containsInAnyOrder("Ana")))
                .andExpect(jsonPath("$.clientes[0]", not(hasKey("limiteCredito"))))
                .andExpect(jsonPath("$.clientes[0]", not(hasKey("listaPreciosId"))))
                .andExpect(jsonPath("$.clientes[0]", not(hasKey("plazoPagoDias"))))
                .andExpect(jsonPath("$.clientes[0].cuentaCorrienteHabilitada").value(false));
        mvc.perform(get("/api/clientes/" + inactivo.getId()).with(empleadoCentro)).andExpect(status().isNotFound());
    }

    @Test
    void elEmpleadoDaDeAltaYEditaDatosPeroNoCondicionesNiDesactiva() throws Exception {
        crear(empleadoCentro, "{\"datos\": {\"nombre\": \"Ana\", \"telefono\": \"351 555-0000\"}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", not(hasKey("limiteCredito"))));
        crear(empleadoCentro, """
                {"datos": {"nombre": "Beto"}, "condiciones": {"cuentaCorrienteHabilitada": false}}""")
                .andExpect(status().isForbidden());

        Cliente carlos = datos.cliente(centro, "Carlos");
        mvc.perform(json(put("/api/clientes/" + carlos.getId()), empleadoCentro, datosCliente("Carlos Gómez", null)))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/clientes/" + carlos.getId() + "/condiciones"), empleadoCentro,
                        condiciones(false, null, null, null)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/clientes/" + carlos.getId() + "/desactivar").with(empleadoCentro))
                .andExpect(status().isForbidden());
    }

    // --- Validaciones ---

    @Test
    void elNombreOLaRazonSocialSonObligatorios() throws Exception {
        crear(duenoCentro, "{\"datos\": {\"nombre\": \"  \", \"telefono\": \"351\"}}").andExpect(status().isBadRequest());
        crear(duenoCentro, "{\"datos\": {\"razonSocial\": \"Vivero SRL\"}}").andExpect(status().isCreated());
    }

    @Test
    void elDocumentoTieneQueSerValido() throws Exception {
        for (Map.Entry<String, String> invalido : Map.of(
                "cuit", "20123456780",
                "dni", "123456",
                "otro", "12345678").entrySet()) {
            crear(duenoCentro, """
                    {"datos": {"nombre": "Ana", "documentoTipo": "%s", "documentoNumero": "%s"}}"""
                    .formatted(invalido.getKey(), invalido.getValue()))
                    .andExpect(status().isBadRequest());
        }
        crear(duenoCentro, "{\"datos\": {\"nombre\": \"Ana\", \"documentoNumero\": \"12345678\"}}")
                .andExpect(status().isBadRequest());
        crear(duenoCentro, "{\"datos\": {\"nombre\": \"Ana\", \"documentoTipo\": \"dni\", \"documentoNumero\": \"12.345.678\"}}")
                .andExpect(status().isBadRequest());
        crear(duenoCentro, "{\"datos\": {\"nombre\": \"Ana\", \"documentoTipo\": \"dni\", \"documentoNumero\": \"1234567\"}}")
                .andExpect(status().isCreated());
    }

    @Test
    void elDocumentoNoSeRepiteEnElLocalPeroSiEnOtro() throws Exception {
        String ana = "{\"datos\": {\"nombre\": \"Ana\", \"documentoTipo\": \"cuit\", \"documentoNumero\": \"%s\"}}"
                .formatted(CUIT);
        crear(duenoCentro, ana).andExpect(status().isCreated());
        crear(duenoCentro, ana).andExpect(status().isConflict());
        crear(datos.en(norte, Perfil.dueno_local), ana).andExpect(status().isCreated());
    }

    @Test
    void laCuentaCorrienteExigeDocumento() throws Exception {
        crear(duenoCentro, """
                {"datos": {"nombre": "Ana"}, "condiciones": {"cuentaCorrienteHabilitada": true}}""")
                .andExpect(status().isConflict());

        String respuesta = crear(duenoCentro, """
                {"datos": {"nombre": "Ana", "documentoTipo": "cuit", "documentoNumero": "%s"},
                 "condiciones": {"cuentaCorrienteHabilitada": true}}""".formatted(CUIT))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(respuesta, "$.id");
        // Sacarle el documento con la cuenta habilitada tampoco se puede.
        mvc.perform(json(put("/api/clientes/" + id), duenoCentro, datosCliente("Ana", null)))
                .andExpect(status().isConflict());
    }

    // --- Fábrica: locales y mayoristas ---

    @Test
    void laFabricaTieneUnClientePorLocalConLosDatosDeSuEntidad() throws Exception {
        mvc.perform(get("/api/clientes").with(duenoFabrica))
                .andExpect(jsonPath("$.clientes[*].nombre").value(containsInAnyOrder("Centro", "Norte")))
                .andExpect(jsonPath("$.clientes[0].localId").value(centro.getId().intValue()))
                .andExpect(jsonPath("$.clientes[0].documentoTipo").value("cuit"))
                .andExpect(jsonPath("$.clientes[0].documentoNumero").value(centro.getEntidadFiscal().getCuit()))
                .andExpect(jsonPath("$.clientes[0].condicionIva").value("monotributo"))
                .andExpect(jsonPath("$.clientes[0].cuentaCorrienteHabilitada").value(true));
        // El cliente que representa a un local es de la fábrica: el local no lo ve.
        mvc.perform(get("/api/clientes/" + datos.clienteDelLocal(centro).getId()).with(duenoCentro))
                .andExpect(status().isNotFound());
    }

    @Test
    void laFabricaDaDeAltaAUnMayoristaConSuListaYCuentaCorriente() throws Exception {
        ListaPrecios mayorista = datos.lista(fabrica, "Mayorista Jardinería", false, false);

        crear(duenoFabrica, """
                {"datos": {"razonSocial": "Vivero Las Flores SRL", "documentoTipo": "cuit",
                           "documentoNumero": "%s", "condicionIva": "responsable_inscripto"},
                 "condiciones": {"cuentaCorrienteHabilitada": true, "limiteCredito": 2000000,
                                 "listaPreciosId": %d, "plazoPagoDias": 30}}
                """.formatted(OTRO_CUIT, mayorista.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.listaPreciosNombre").value("Mayorista Jardinería"))
                .andExpect(jsonPath("$.localId").isEmpty());

        mvc.perform(get("/api/clientes").with(duenoFabrica))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.clientes[2].razonSocial").value("Vivero Las Flores SRL"));
        // Una lista de un local no sirve para un cliente de la fábrica.
        ListaPrecios deCentro = datos.lista(centro, "Mayorista", true, false);
        crear(duenoFabrica, """
                {"datos": {"nombre": "Paisajista"},
                 "condiciones": {"cuentaCorrienteHabilitada": false, "listaPreciosId": %d}}
                """.formatted(deCentro.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deUnLocalLaFabricaSoloCambiaElPlazoYLaLista() throws Exception {
        Cliente cliente = datos.clienteDelLocal(centro);
        ListaPrecios paraLocales = datos.lista(fabrica, "Para locales", false, false);

        mvc.perform(json(put("/api/clientes/" + cliente.getId() + "/condiciones"), duenoFabrica,
                        condiciones(true, null, paraLocales.getId(), 15)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plazoPagoDias").value(15))
                .andExpect(jsonPath("$.listaPreciosNombre").value("Para locales"));
        mvc.perform(json(put("/api/clientes/" + cliente.getId() + "/condiciones"), duenoFabrica,
                        condiciones(false, null, null, 15)))
                .andExpect(status().isConflict());
        mvc.perform(json(put("/api/clientes/" + cliente.getId() + "/condiciones"), duenoFabrica,
                        condiciones(true, "1000", null, 15)))
                .andExpect(status().isConflict());
        mvc.perform(json(put("/api/clientes/" + cliente.getId()), duenoFabrica, datosCliente("Otro nombre", null)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/clientes/" + cliente.getId() + "/desactivar").with(duenoFabrica))
                .andExpect(status().isConflict());
    }

    @Test
    void elCambioDeCondicionesDeUnLocalQuedaEnAuditoriaParaLaFabricaYParaElLocal() throws Exception {
        Cliente cliente = datos.clienteDelLocal(centro);

        mvc.perform(json(put("/api/clientes/" + cliente.getId() + "/condiciones"), duenoFabrica,
                        condiciones(true, null, null, 20)))
                .andExpect(status().isOk());

        assertThat(eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("editar_condiciones")))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.ambitosAfectados()).containsExactly(fabrica.getId(), centro.getId());
                    assertThat(e.detalle()).containsOnlyKeys("plazoPagoDias");
                });
    }

    @Test
    void losDatosDelClienteDeUnLocalSiguenALosDelLocalYSuEntidad() throws Exception {
        RequestPostProcessor admin = datos.admin();
        mvc.perform(json(put("/api/admin/ambitos/" + centro.getId()), admin, """
                {"nombre": "Centro Renovado", "visibleWeb": true}""")).andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/entidades-fiscales/" + centro.getEntidadFiscal().getId()), admin, """
                {"razonSocial": "Centro SA", "cuit": "%s", "condicionIva": "responsable_inscripto"}
                """.formatted(OTRO_CUIT))).andExpect(status().isOk());

        mvc.perform(get("/api/clientes/" + datos.clienteDelLocal(centro).getId()).with(duenoFabrica))
                .andExpect(jsonPath("$.nombre").value("Centro Renovado"))
                .andExpect(jsonPath("$.razonSocial").value("Centro SA"))
                .andExpect(jsonPath("$.documentoNumero").value(OTRO_CUIT))
                .andExpect(jsonPath("$.condicionIva").value(CondicionIva.responsable_inscripto.name()));
    }

    // --- Auxiliares ---

    private ResultActions crear(RequestPostProcessor sesion, String cuerpo)
            throws Exception {
        return mvc.perform(json(post("/api/clientes"), sesion, cuerpo));
    }

    private static String datosCliente(String nombre, String documento) {
        return documento == null
                ? "{\"nombre\": \"%s\"}".formatted(nombre)
                : "{\"nombre\": \"%s\", \"documentoTipo\": \"cuit\", \"documentoNumero\": \"%s\"}"
                        .formatted(nombre, documento);
    }

    private static String condiciones(boolean cuentaCorriente, String limite, Long listaId, Integer plazo) {
        return """
                {"cuentaCorrienteHabilitada": %s, "limiteCredito": %s, "listaPreciosId": %s, "plazoPagoDias": %s}
                """.formatted(cuentaCorriente, limite, listaId, plazo);
    }
}
