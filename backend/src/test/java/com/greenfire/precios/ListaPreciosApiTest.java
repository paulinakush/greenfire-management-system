package com.greenfire.precios;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.seguridad.Perfil;
import com.jayway.jsonpath.JsonPath;

@PruebaIntegracion
class ListaPreciosApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;
    @Autowired
    ObjectMapper mapper;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Categoria sustratos;
    Categoria macetas;
    Articulo turba;
    Articulo cocoElite;
    Articulo macetaCentro;
    Articulo macetaNorte;

    /** La fábrica y el local Norte son responsables inscriptos; el local Centro es monotributista. */
    @BeforeEach
    void catalogo() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.ambito(TipoAmbito.local, "Norte", CondicionIva.responsable_inscripto);
        sustratos = datos.categoria("Sustratos");
        macetas = datos.categoria("Macetas");
        turba = datos.articulo(fabrica, TipoArticulo.insumo, "Turba", null);
        cocoElite = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco Elite", sustratos);
        macetaCentro = datos.articulo(centro, TipoArticulo.reventa, "Maceta 5 L", macetas);
        macetaNorte = datos.articulo(norte, TipoArticulo.reventa, "Maceta 10 L", macetas);
    }

    // --- IVA y margen según la condición fiscal ---

    @Test
    void elMargenDeUnMismoArticuloDependeDeLaCondicionFiscalYDeSiLaListaEsConIva() throws Exception {
        datos.costo(cocoElite, norte, "600");
        datos.costo(cocoElite, centro, "600");
        ListaPrecios norteConIva = datos.lista(norte, "Con IVA", true, false);
        ListaPrecios norteSinIva = datos.lista(norte, "Sin IVA", false, false);
        ListaPrecios centroFinal = datos.lista(centro, "Público", true, true);
        var duenoNorte = datos.en(norte, Perfil.dueno_local);
        var duenoCentro = datos.en(centro, Perfil.dueno_local);

        // responsable inscripto, precios con IVA: el margen se calcula sobre 1210 / 1,21 = 1000
        precio(norteConIva, cocoElite, duenoNorte, "1210")
                .andExpect(jsonPath("$.precioNeto").value(1000.0))
                .andExpect(jsonPath("$.costo").value(600.0))
                .andExpect(jsonPath("$.margen").value(0.4));
        // responsable inscripto, precios sin IVA: el precio ya es neto
        precio(norteSinIva, cocoElite, duenoNorte, "1210")
                .andExpect(jsonPath("$.precioNeto").value(1210.0))
                .andExpect(jsonPath("$.margen").value(0.5041));
        // monotributista: el precio es final y el margen se calcula sobre él, sin sacar IVA
        precio(centroFinal, cocoElite, duenoCentro, "1210")
                .andExpect(jsonPath("$.precioNeto").value(1210.0))
                .andExpect(jsonPath("$.margen").value(0.5041));

        mvc.perform(get("/api/listas-precios/" + norteConIva.getId() + "/items").with(duenoNorte))
                .andExpect(jsonPath("$[0].margen").value(0.4));
        mvc.perform(get("/api/listas-precios").with(duenoCentro))
                .andExpect(jsonPath("$[0].discriminaIva").value(false));
    }

    @Test
    void siLaEntidadNoDiscriminaIvaLaListaNoPuedeSerSinIva() throws Exception {
        mvc.perform(json(post("/api/listas-precios"), datos.en(centro, Perfil.dueno_local),
                        "{\"nombre\": \"Mayorista\", \"preciosConIva\": false}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/listas-precios"), datos.en(norte, Perfil.dueno_local),
                        "{\"nombre\": \"Mayorista\", \"preciosConIva\": false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discriminaIva").value(true));
    }

    @Test
    void sinCostoDelAmbitoNoHayMargenYElLocalNoUsaElCostoDeLaFabrica() throws Exception {
        datos.costo(cocoElite, fabrica, "300");
        ListaPrecios lista = datos.lista(centro, "Público", true, true);

        precio(lista, cocoElite, datos.en(centro, Perfil.dueno_local), "1000")
                .andExpect(jsonPath("$.costo").value(nullValue()))
                .andExpect(jsonPath("$.margen").value(nullValue()));
    }

    @Test
    void siCambiaLaCondicionFiscalLosPreciosQuedanComoEstanConAdvertenciaYAuditoria() throws Exception {
        ListaPrecios sinIva = datos.lista(norte, "Público", false, true);
        ListaPrecios conIva = datos.lista(norte, "Mayorista", true, false);
        datos.precio(sinIva, macetaNorte, "1000");
        datos.precio(conIva, macetaNorte, "1210");
        datos.lista(centro, "De otra entidad", true, true);
        var entidad = norte.getEntidadFiscal();

        mvc.perform(json(put("/api/admin/entidades-fiscales/" + entidad.getId()), datos.admin(), """
                        {"razonSocial": "%s", "cuit": "%s", "condicionIva": "monotributo"}
                        """.formatted(entidad.getRazonSocial(), entidad.getCuit())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condicionIva").value("monotributo"))
                .andExpect(jsonPath("$.advertencia.mensaje").exists())
                .andExpect(jsonPath("$.advertencia.listasAfectadas[*].nombre").value(contains("Mayorista", "Público")))
                .andExpect(jsonPath("$.advertencia.listasAfectadas[*].ambitoId")
                        .value(contains(norte.getId().intValue(), norte.getId().intValue())))
                .andExpect(jsonPath("$.advertencia.listasAfectadas[*].preciosConIva").value(contains(true, false)));

        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.entidad()).isEqualTo("entidad_fiscal");
        assertThat(evento.ambitosAfectados()).containsExactly(norte.getId());
        assertThat(evento.detalle()).containsOnlyKeys("condicionIva");
        assertThat(evento.detalle().get("condicionIva").toString())
                .isEqualTo("{antes=responsable_inscripto, despues=monotributo}");

        // los precios no se recalculan; desde ahora el precio de la lista es el final
        var dueno = datos.en(norte, Perfil.dueno_local);
        mvc.perform(get("/api/listas-precios/" + sinIva.getId() + "/items").with(dueno))
                .andExpect(jsonPath("$[0].precio").value(1000.0))
                .andExpect(jsonPath("$[0].precioNeto").value(1000.0));
        mvc.perform(get("/api/precios").with(datos.en(norte, Perfil.empleado_local)))
                .andExpect(jsonPath("$[0].precio").value(1000.0));
        // y la lista sin IVA se sigue pudiendo editar
        mvc.perform(json(put("/api/listas-precios/" + sinIva.getId()), dueno,
                        "{\"nombre\": \"Público viejo\", \"preciosConIva\": false, \"esPredeterminada\": true}"))
                .andExpect(status().isOk());
    }

    @Test
    void sinCambioDeCondicionFiscalNoHayAdvertencia() throws Exception {
        datos.lista(norte, "Público", false, true);
        var entidad = norte.getEntidadFiscal();

        mvc.perform(json(put("/api/admin/entidades-fiscales/" + entidad.getId()), datos.admin(), """
                        {"razonSocial": "Otra razón social", "cuit": "%s", "condicionIva": "responsable_inscripto"}
                        """.formatted(entidad.getCuit())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.razonSocial").value("Otra razón social"))
                .andExpect(jsonPath("$.advertencia").value(nullValue()));
    }

    // --- Listas ---

    @Test
    void cadaDuenoAdministraLasListasDeSuAmbitoYQuedanEnAuditoria() throws Exception {
        var dueno = datos.en(fabrica, Perfil.dueno_fabrica);

        String cuerpo = mvc.perform(json(post("/api/listas-precios"), dueno,
                        "{\"nombre\": \"Locales\", \"preciosConIva\": false, \"esPredeterminada\": true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.esPredeterminada").value(true))
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(cuerpo, "$.id")).longValue();
        mvc.perform(json(put("/api/listas-precios/" + id), dueno,
                        "{\"nombre\": \"Locales 2027\", \"preciosConIva\": true, \"esPredeterminada\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preciosConIva").value(true));

        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion)).containsExactly("crear", "editar");
        EventoAuditoria edicion = eventos.stream(EventoAuditoria.class).skip(1).findFirst().orElseThrow();
        assertThat(edicion.entidad()).isEqualTo("lista_precios");
        assertThat(edicion.ambitosAfectados()).containsExactly(fabrica.getId());
        assertThat(edicion.detalle()).containsOnlyKeys("nombre", "preciosConIva");
    }

    @Test
    void elNombreDeLaListaEsUnicoEnElAmbito() throws Exception {
        datos.lista(norte, "Mayorista", true, false);
        datos.lista(centro, "Mayorista", true, false);

        mvc.perform(json(post("/api/listas-precios"), datos.en(norte, Perfil.dueno_local),
                        "{\"nombre\": \"MAYORISTA\", \"preciosConIva\": true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void hayUnaSolaPredeterminadaPorAmbitoYNoSePuedeDesactivar() throws Exception {
        ListaPrecios publico = datos.lista(centro, "Público", true, true);
        ListaPrecios otra = datos.lista(centro, "Otra", true, false);
        var dueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(post("/api/listas-precios/" + publico.getId() + "/desactivar").with(dueno))
                .andExpect(status().isConflict());

        // al elegir otra como predeterminada, la anterior deja de serlo
        mvc.perform(json(put("/api/listas-precios/" + otra.getId()), dueno,
                        "{\"nombre\": \"Otra\", \"preciosConIva\": true, \"esPredeterminada\": true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/listas-precios").with(dueno))
                .andExpect(jsonPath("$[?(@.esPredeterminada == true)].nombre").value(contains("Otra")));
        mvc.perform(post("/api/listas-precios/" + publico.getId() + "/desactivar").with(dueno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));
    }

    @Test
    void siLaListaYaTienePreciosNoSeCambiaSiSonConOSinIva() throws Exception {
        ListaPrecios lista = datos.lista(norte, "Público", true, true);
        datos.precio(lista, macetaNorte, "500");

        mvc.perform(json(put("/api/listas-precios/" + lista.getId()), datos.en(norte, Perfil.dueno_local),
                        "{\"nombre\": \"Público\", \"preciosConIva\": false, \"esPredeterminada\": true}"))
                .andExpect(status().isConflict());
    }

    @Test
    void losPreciosDeUnaListaDesactivadaNoSeCambian() throws Exception {
        ListaPrecios lista = datos.lista(centro, "Vieja", true, false);
        lista.setActivo(false);

        precio(lista, macetaCentro, datos.en(centro, Perfil.dueno_local), "500").andExpect(status().isConflict());
    }

    // --- Qué ve cada ámbito ---

    @Test
    void nadieVeNiTocaLasListasDeOtroAmbito() throws Exception {
        ListaPrecios deFabrica = datos.lista(fabrica, "Locales", false, true);
        ListaPrecios deNorte = datos.lista(norte, "Público", true, true);
        datos.precio(deFabrica, cocoElite, "800");
        var duenoCentro = datos.en(centro, Perfil.dueno_local);

        mvc.perform(get("/api/listas-precios").with(duenoCentro)).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/listas-precios").with(datos.en(fabrica, Perfil.dueno_fabrica)))
                .andExpect(jsonPath("$[*].nombre").value(contains("Locales")));

        for (ListaPrecios ajena : List.of(deFabrica, deNorte)) {
            String base = "/api/listas-precios/" + ajena.getId();
            mvc.perform(get(base + "/items").with(duenoCentro)).andExpect(status().isNotFound());
            mvc.perform(get(base + "/historial").with(duenoCentro)).andExpect(status().isNotFound());
            precio(ajena, cocoElite, duenoCentro, "1").andExpect(status().isNotFound());
            mvc.perform(delete(base + "/items/" + cocoElite.getId()).with(duenoCentro))
                    .andExpect(status().isNotFound());
            mvc.perform(json(put(base), duenoCentro, "{\"nombre\": \"X\", \"preciosConIva\": true}"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(base + "/desactivar").with(duenoCentro)).andExpect(status().isNotFound());
            mvc.perform(json(post(base + "/actualizacion-masiva"), duenoCentro, "{\"porcentaje\": 10}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void unaListaTienePreciosDeLoQueVendeSuAmbito() throws Exception {
        var duenoFabrica = datos.en(fabrica, Perfil.dueno_fabrica);
        var duenoCentro = datos.en(centro, Perfil.dueno_local);
        ListaPrecios deFabrica = datos.lista(fabrica, "Locales", true, true);
        ListaPrecios deCentro = datos.lista(centro, "Público", true, true);

        precio(deFabrica, cocoElite, duenoFabrica, "800").andExpect(status().isOk());
        precio(deFabrica, turba, duenoFabrica, "100").andExpect(status().isConflict());
        precio(deFabrica, macetaCentro, duenoFabrica, "100").andExpect(status().isNotFound());

        precio(deCentro, cocoElite, duenoCentro, "1000").andExpect(status().isOk());
        precio(deCentro, macetaCentro, duenoCentro, "500").andExpect(status().isOk());
        precio(deCentro, macetaNorte, duenoCentro, "500").andExpect(status().isNotFound());
        precio(deCentro, turba, duenoCentro, "100").andExpect(status().isNotFound());

        macetaCentro.setActivo(false);
        precio(deCentro, macetaCentro, duenoCentro, "600").andExpect(status().isConflict());
    }

    // --- Cambios de precio e historial ---

    @Test
    void cadaCambioDePrecioQuedaEnElHistorialYEnLaAuditoria() throws Exception {
        ListaPrecios lista = datos.lista(centro, "Público", true, true);
        var dueno = datos.en(centro, Perfil.dueno_local);

        precio(lista, macetaCentro, dueno, "500").andExpect(status().isOk());
        precio(lista, macetaCentro, dueno, "550").andExpect(status().isOk());
        precio(lista, macetaCentro, dueno, "550.00").andExpect(status().isOk()); // igual: no deja registro
        precio(lista, cocoElite, dueno, "1000").andExpect(status().isOk());
        mvc.perform(delete("/api/listas-precios/" + lista.getId() + "/items/" + macetaCentro.getId()).with(dueno))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/listas-precios/" + lista.getId() + "/items/" + macetaCentro.getId()).with(dueno))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/listas-precios/" + lista.getId() + "/items").with(dueno))
                .andExpect(jsonPath("$[*].articuloNombre").value(contains("Coco Elite")));
        mvc.perform(get("/api/listas-precios/" + lista.getId() + "/historial")
                        .param("articuloId", macetaCentro.getId().toString()).with(dueno))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.registros[0].precioAnterior").value(550.0))
                .andExpect(jsonPath("$.registros[0].precioNuevo").value(nullValue()))
                .andExpect(jsonPath("$.registros[1].precioAnterior").value(500.0))
                .andExpect(jsonPath("$.registros[1].precioNuevo").value(550.0))
                .andExpect(jsonPath("$.registros[2].precioAnterior").value(nullValue()))
                .andExpect(jsonPath("$.registros[2].precioNuevo").value(500.0));
        mvc.perform(get("/api/listas-precios/" + lista.getId() + "/historial").with(dueno))
                .andExpect(jsonPath("$.total").value(4));

        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .containsExactly("editar_precio", "editar_precio", "editar_precio", "quitar_precio");
        EventoAuditoria cambio = eventos.stream(EventoAuditoria.class).skip(1).findFirst().orElseThrow();
        assertThat(cambio.ambitosAfectados()).containsExactly(centro.getId());
        assertThat(cambio.entidadId()).isEqualTo(lista.getId());
        assertThat(cambio.detalle().get("articuloId")).isEqualTo(macetaCentro.getId());
        assertThat(cambio.detalle().get("precio").toString()).isEqualTo("{antes=500.00, despues=550.00}");
    }

    // --- Actualización masiva ---

    @Test
    void laActualizacionMasivaCambiaLosPreciosFiltradosRedondeaYAuditaUnaVez() throws Exception {
        ListaPrecios lista = datos.lista(centro, "Público", true, true);
        datos.precio(lista, cocoElite, "1000");
        datos.precio(lista, macetaCentro, "1234");
        Articulo regadera = datos.articulo(centro, TipoArticulo.reventa, "Regadera", macetas);
        datos.precio(lista, regadera, "100");
        var dueno = datos.en(centro, Perfil.dueno_local);

        // solo macetas: 1234 → 1357,40 → 1360; 100 → 110
        mvc.perform(json(post("/api/listas-precios/" + lista.getId() + "/actualizacion-masiva"), dueno,
                        "{\"porcentaje\": 10, \"categoriaId\": %d, \"redondeo\": 10}".formatted(macetas.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualizados").value(2));

        mvc.perform(get("/api/listas-precios/" + lista.getId() + "/items").with(dueno))
                .andExpect(jsonPath("$[*].articuloNombre").value(contains("Coco Elite", "Maceta 5 L", "Regadera")))
                .andExpect(jsonPath("$[*].precio").value(contains(1000.0, 1360.0, 110.0)));
        mvc.perform(get("/api/listas-precios/" + lista.getId() + "/historial").with(dueno))
                .andExpect(jsonPath("$.total").value(2));

        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .containsExactly("actualizacion_masiva");
        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.ambitosAfectados()).containsExactly(centro.getId());
        assertThat(evento.detalle().get("actualizados")).isEqualTo(2);
    }

    @Test
    void siAlgunPrecioQuedariaEnCeroNoSeAplicaNinguno() throws Exception {
        ListaPrecios lista = datos.lista(centro, "Público", true, true);
        datos.precio(lista, cocoElite, "1000");
        datos.precio(lista, macetaCentro, "20");
        var dueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(json(post("/api/listas-precios/" + lista.getId() + "/actualizacion-masiva"), dueno,
                        "{\"porcentaje\": -50, \"redondeo\": 100}"))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/listas-precios/" + lista.getId() + "/actualizacion-masiva"), dueno,
                        "{\"porcentaje\": -100}"))
                .andExpect(status().isBadRequest());
    }

    // --- Empleado y otros perfiles ---

    @Test
    void elEmpleadoSoloVeArticuloYPrecioDeVentaDeSuLocal() throws Exception {
        datos.costo(macetaCentro, centro, "300");
        ListaPrecios publico = datos.lista(centro, "Público", true, true);
        ListaPrecios mayorista = datos.lista(centro, "Mayorista", true, false);
        datos.precio(publico, macetaCentro, "500");
        datos.precio(publico, cocoElite, "1000");
        datos.precio(mayorista, macetaCentro, "400");
        Articulo inactivo = datos.articulo(centro, TipoArticulo.reventa, "Regadera", macetas);
        datos.precio(publico, inactivo, "100");
        inactivo.setActivo(false);
        var empleado = datos.en(centro, Perfil.empleado_local);

        String cuerpo = mvc.perform(get("/api/precios").with(empleado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].articuloNombre").value(containsInAnyOrder("Coco Elite", "Maceta 5 L")))
                .andExpect(jsonPath("$[?(@.articuloNombre == 'Maceta 5 L')].precio").value(contains(500.0)))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> lista = mapper.readValue(cuerpo, mapper.getTypeFactory()
                .constructCollectionType(List.class, Map.class));
        assertThat(lista).allSatisfy(p -> assertThat(p.keySet())
                .containsExactlyInAnyOrder("articuloId", "articuloNombre", "precio"));

        mvc.perform(get("/api/listas-precios").with(empleado)).andExpect(status().isForbidden());
        mvc.perform(get("/api/listas-precios/" + publico.getId() + "/items").with(empleado))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/listas-precios/" + publico.getId() + "/historial").with(empleado))
                .andExpect(status().isForbidden());
        precio(publico, macetaCentro, empleado, "1").andExpect(status().isForbidden());
        mvc.perform(json(post("/api/listas-precios"), empleado, "{\"nombre\": \"X\", \"preciosConIva\": true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void siElLocalDiscriminaIvaYLaListaEsSinIvaElEmpleadoVeElPrecioConIva() throws Exception {
        ListaPrecios publico = datos.lista(norte, "Público", false, true);
        datos.precio(publico, macetaNorte, "1000");

        mvc.perform(get("/api/precios").with(datos.en(norte, Perfil.empleado_local)))
                .andExpect(jsonPath("$[0].precio").value(1210.0));
    }

    @Test
    void sinListaPredeterminadaElEmpleadoNoVePrecios() throws Exception {
        datos.precio(datos.lista(centro, "Mayorista", true, false), macetaCentro, "400");

        mvc.perform(get("/api/precios").with(datos.en(centro, Perfil.empleado_local)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void elAdministradorTecnicoNoVePrecios() throws Exception {
        ListaPrecios lista = datos.lista(fabrica, "Locales", true, true);

        mvc.perform(get("/api/listas-precios").with(datos.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/listas-precios/" + lista.getId() + "/items").with(datos.admin()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/precios").with(datos.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/precios").with(datos.en(fabrica, Perfil.dueno_fabrica)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinSesionResponde401() throws Exception {
        mvc.perform(get("/api/listas-precios")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/precios")).andExpect(status().isUnauthorized());
    }

    private ResultActions precio(ListaPrecios lista, Articulo articulo,
            RequestPostProcessor sesion, String precio) throws Exception {
        return mvc.perform(json(put("/api/listas-precios/" + lista.getId() + "/items/" + articulo.getId()), sesion,
                "{\"precio\": " + precio + "}"));
    }
}
