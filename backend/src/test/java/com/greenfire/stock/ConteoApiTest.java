package com.greenfire.stock;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.seguridad.Perfil;

@PruebaIntegracion
class ConteoApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Articulo turba;
    Articulo coco;
    Articulo macetaCentro;
    Articulo macetaNorte;
    UbicacionStock enCentro;
    RequestPostProcessor dueno;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        turba = datos.articulo(fabrica, TipoArticulo.insumo, "Turba", null);
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco Elite", datos.categoria("Sustratos"));
        macetaCentro = datos.articulo(centro, TipoArticulo.reventa, "Maceta 5 L", datos.categoria("Macetas"));
        macetaNorte = datos.articulo(norte, TipoArticulo.reventa, "Maceta 10 L", datos.categoria("Macetas"));
        enCentro = datos.principal(centro);
        dueno = datos.en(centro, Perfil.dueno_local);
    }

    // --- Cierre ---

    @Test
    void unCierreConDiferenciasLasRegistraComoMovimientosYLasAudita() throws Exception {
        datos.stock(coco, enCentro, "10");
        datos.stock(macetaCentro, enCentro, "5");
        long id = abrir(dueno, enCentro);
        cargar(dueno, id, item(coco, "8"), item(macetaCentro, "7")).andExpect(status().isOk());

        cerrar(dueno, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cerrado"))
                .andExpect(jsonPath("$.cerradoAt").exists())
                .andExpect(jsonPath("$.items[*].articuloNombre").value(contains("Coco Elite", "Maceta 5 L")))
                .andExpect(jsonPath("$.items[*].cantidadSistema").value(contains(10.0, 5.0)))
                .andExpect(jsonPath("$.items[*].diferencia").value(contains(-2.0, 2.0)));

        assertThat(datos.cantidad(coco, enCentro)).isEqualByComparingTo("8");
        assertThat(datos.cantidad(macetaCentro, enCentro)).isEqualByComparingTo("7");
        mvc.perform(get("/api/stock/movimientos").param("tipo", "conteo").with(dueno))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.movimientos[*].conteoId").value(everyItem(equalTo((int) id))));

        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .containsExactly("abrir", "cargar", "cerrar");
        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::ambitosAfectados))
                .allSatisfy(a -> assertThat(a).containsExactly(centro.getId()));
        EventoAuditoria cierre = eventos.stream(EventoAuditoria.class).skip(2).findFirst().orElseThrow();
        assertThat(cierre.entidad()).isEqualTo("conteo_stock");
        assertThat(cierre.detalle().get("articulosContados")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diferencias = (List<Map<String, Object>>) cierre.detalle().get("diferencias");
        assertThat(diferencias).hasSize(2);
        assertThat(diferencias.get(0)).containsKeys("articuloId", "cantidadSistema", "cantidadContada",
                "diferencia", "movimientoId");
    }

    @Test
    void unCierreSinDiferenciasNoGeneraMovimientos() throws Exception {
        datos.stock(coco, enCentro, "10");
        long id = abrir(dueno, enCentro);
        cargar(dueno, id, item(coco, "10")).andExpect(status().isOk());

        cerrar(dueno, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("cerrado"))
                .andExpect(jsonPath("$.items[0].diferencia").value(0.0));

        assertThat(movimientosDeConteo(id)).isZero();
        assertThat(datos.cantidad(coco, enCentro)).isEqualByComparingTo("10");
        EventoAuditoria cierre = eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("cerrar"))
                .findFirst().orElseThrow();
        assertThat((List<?>) cierre.detalle().get("diferencias")).isEmpty();
    }

    @Test
    void laCargaInicialEsUnConteoContraCeroDeArticulosSinStock() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from stock_articulos where ubicacion_stock_id = ?",
                Integer.class, enCentro.getId())).isZero();
        long id = abrir(dueno, enCentro);
        cargar(dueno, id, item(coco, "12"), item(macetaCentro, "3")).andExpect(status().isOk());

        cerrar(dueno, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].cantidadSistema").value(contains(0.0, 0.0)))
                .andExpect(jsonPath("$.items[*].diferencia").value(contains(12.0, 3.0)));

        assertThat(datos.cantidad(coco, enCentro)).isEqualByComparingTo("12");
        assertThat(datos.cantidad(macetaCentro, enCentro)).isEqualByComparingTo("3");
        assertThat(movimientosDeConteo(id)).isEqualTo(2);
        assertThat(jdbc.queryForList("select distinct tipo from movimientos_stock where ubicacion_stock_id = ?",
                String.class, enCentro.getId())).containsExactly("conteo");
    }

    @Test
    void unConteoSinArticulosNoSeCierra() throws Exception {
        cerrar(dueno, abrir(dueno, enCentro)).andExpect(status().isConflict());
    }

    // --- Carga ---

    @Test
    void mientrasEstaAbiertoSeCargaDeAPartesSinVerLaCantidadDelSistema() throws Exception {
        datos.stock(coco, enCentro, "10");
        long id = abrir(dueno, enCentro);

        cargar(dueno, id, item(coco, "4")).andExpect(status().isOk());
        cargar(dueno, id, item(macetaCentro, "2"), item(coco, "9")).andExpect(status().isOk());
        cargar(dueno, id, item(macetaCentro, null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].articuloNombre").value(contains("Coco Elite")))
                .andExpect(jsonPath("$.items[0].cantidadContada").value(9.0))
                .andExpect(jsonPath("$.items[0].cantidadSistema").value(nullValue()))
                .andExpect(jsonPath("$.items[0].diferencia").value(nullValue()));
        cargar(dueno, id, item(coco, "9")).andExpect(status().isOk()); // sin cambios: no audita

        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .containsExactly("abrir", "cargar", "cargar", "cargar");
        EventoAuditoria segunda = eventos.stream(EventoAuditoria.class).skip(2).findFirst().orElseThrow();
        assertThat((List<?>) segunda.detalle().get("items")).hasSize(2);
    }

    @Test
    void soloSeCuentanArticulosQueElAmbitoVeEnCantidadesValidas() throws Exception {
        long id = abrir(dueno, enCentro);

        cargar(dueno, id, item(turba, "1")).andExpect(status().isNotFound());
        cargar(dueno, id, item(macetaNorte, "1")).andExpect(status().isNotFound());
        cargar(dueno, id, item(coco, "1.5")).andExpect(status().isConflict());
        cargar(dueno, id, item(coco, "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conteos/" + id).with(dueno)).andExpect(jsonPath("$.items", hasSize(0)));
    }

    // --- Un conteo abierto por ubicación; cerrado o anulado no se modifica ---

    @Test
    void noPuedeHaberDosConteosAbiertosEnLaMismaUbicacion() throws Exception {
        long primero = abrir(dueno, enCentro);
        abrirRespuesta(dueno, enCentro).andExpect(status().isConflict());
        abrirRespuesta(dueno, datos.ubicacion(centro, "Depósito")).andExpect(status().isCreated());

        anular(dueno, primero).andExpect(status().isOk());
        abrirRespuesta(dueno, enCentro).andExpect(status().isCreated());
        mvc.perform(get("/api/conteos").param("estado", "abierto").with(dueno)).andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void unConteoCerradoNoSeModificaNiSeAnula() throws Exception {
        long id = abrir(dueno, enCentro);
        cargar(dueno, id, item(coco, "1")).andExpect(status().isOk());
        cerrar(dueno, id).andExpect(status().isOk());

        cargar(dueno, id, item(coco, "2")).andExpect(status().isConflict());
        cerrar(dueno, id).andExpect(status().isConflict());
        anular(dueno, id).andExpect(status().isConflict());
        assertThat(datos.cantidad(coco, enCentro)).isEqualByComparingTo("1");
    }

    @Test
    void soloSeAnulaUnConteoAbiertoYNoGeneraMovimientos() throws Exception {
        datos.stock(coco, enCentro, "10");
        long id = abrir(dueno, enCentro);
        cargar(dueno, id, item(coco, "3")).andExpect(status().isOk());

        anular(dueno, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("anulado"))
                .andExpect(jsonPath("$.anuladoAt").exists());
        cargar(dueno, id, item(coco, "4")).andExpect(status().isConflict());
        cerrar(dueno, id).andExpect(status().isConflict());
        anular(dueno, id).andExpect(status().isConflict());

        assertThat(movimientosDeConteo(id)).isZero();
        assertThat(datos.cantidad(coco, enCentro)).isEqualByComparingTo("10");
        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .containsExactly("abrir", "cargar", "anular");
    }

    @Test
    void noSeAbreUnConteoEnUnaUbicacionDesactivada() throws Exception {
        UbicacionStock vieja = datos.ubicacion(centro, "Depósito viejo");
        vieja.setActivo(false);

        abrirRespuesta(dueno, vieja).andExpect(status().isConflict());
    }

    // --- Acceso ---

    @Test
    void elEmpleadoYElAdministradorTecnicoNoParticipan() throws Exception {
        long id = abrir(dueno, enCentro);
        for (RequestPostProcessor sesion : List.of(datos.en(centro, Perfil.empleado_local), datos.admin())) {
            mvc.perform(get("/api/conteos").with(sesion)).andExpect(status().isForbidden());
            mvc.perform(get("/api/conteos/" + id).with(sesion)).andExpect(status().isForbidden());
            abrirRespuesta(sesion, datos.ubicacion(centro, "Otra " + System.nanoTime()))
                    .andExpect(status().isForbidden());
            cargar(sesion, id, item(coco, "1")).andExpect(status().isForbidden());
            cerrar(sesion, id).andExpect(status().isForbidden());
            anular(sesion, id).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/conteos")).andExpect(status().isUnauthorized());
    }

    @Test
    void nadieVeNiTocaLosConteosDeOtroAmbito() throws Exception {
        long id = abrir(dueno, enCentro);
        cargar(dueno, id, item(coco, "1")).andExpect(status().isOk());
        UbicacionStock enNorte = datos.principal(norte);
        long deNorte = abrir(datos.en(norte, Perfil.dueno_local), enNorte);

        for (RequestPostProcessor ajeno : List.of(datos.en(norte, Perfil.dueno_local),
                datos.en(fabrica, Perfil.dueno_fabrica))) {
            mvc.perform(get("/api/conteos/" + id).with(ajeno)).andExpect(status().isNotFound());
            cargar(ajeno, id, item(coco, "5")).andExpect(status().isNotFound());
            cerrar(ajeno, id).andExpect(status().isNotFound());
            anular(ajeno, id).andExpect(status().isNotFound());
        }
        abrirRespuesta(dueno, enNorte).andExpect(status().isNotFound());
        mvc.perform(get("/api/conteos").with(dueno)).andExpect(jsonPath("$[*].id").value(contains((int) id)));
        mvc.perform(get("/api/conteos").param("ubicacionId", enNorte.getId().toString()).with(dueno))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/api/conteos/" + deNorte).with(dueno)).andExpect(status().isNotFound());
        mvc.perform(get("/api/conteos/" + id).with(dueno)).andExpect(jsonPath("$.estado").value("abierto"));
    }

    // --- Ayudas ---

    private long abrir(RequestPostProcessor sesion, UbicacionStock ubicacion) throws Exception {
        String cuerpo = abrirRespuesta(sesion, ubicacion).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(cuerpo).get("id").asLong();
    }

    private ResultActions abrirRespuesta(RequestPostProcessor sesion, UbicacionStock ubicacion) throws Exception {
        return mvc.perform(json(post("/api/conteos"), sesion, "{\"ubicacionId\": %d}".formatted(ubicacion.getId())));
    }

    private ResultActions cargar(RequestPostProcessor sesion, long id, String... items) throws Exception {
        return mvc.perform(json(put("/api/conteos/" + id + "/items"), sesion,
                "{\"items\": [%s]}".formatted(String.join(", ", items))));
    }

    private ResultActions cerrar(RequestPostProcessor sesion, long id) throws Exception {
        return mvc.perform(post("/api/conteos/" + id + "/cerrar").with(sesion));
    }

    private ResultActions anular(RequestPostProcessor sesion, long id) throws Exception {
        return mvc.perform(post("/api/conteos/" + id + "/anular").with(sesion));
    }

    private static String item(Articulo articulo, String cantidad) {
        return "{\"articuloId\": %d, \"cantidadContada\": %s}".formatted(articulo.getId(), cantidad);
    }

    private int movimientosDeConteo(long conteo) {
        return jdbc.queryForObject("select count(*) from movimientos_stock where conteo_id = ?", Integer.class,
                conteo);
    }
}
