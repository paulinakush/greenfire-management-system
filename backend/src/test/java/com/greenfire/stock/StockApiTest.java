package com.greenfire.stock;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
class StockApiTest {

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
    UbicacionStock enFabrica;
    UbicacionStock enCentro;
    UbicacionStock enNorte;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        turba = datos.articulo(fabrica, TipoArticulo.insumo, "Turba", null);
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco Elite", datos.categoria("Sustratos"));
        macetaCentro = datos.articulo(centro, TipoArticulo.reventa, "Maceta 5 L", datos.categoria("Macetas"));
        macetaNorte = datos.articulo(norte, TipoArticulo.reventa, "Maceta 10 L", datos.categoria("Macetas"));
        enFabrica = datos.principal(fabrica);
        enCentro = datos.principal(centro);
        enNorte = datos.principal(norte);
    }

    // --- Ajustes ---

    @Test
    void unAjusteConMotivoMueveElStockYQuedaEnAuditoriaConElAmbitoAfectado() throws Exception {
        var dueno = datos.en(centro, Perfil.dueno_local);

        ajuste(dueno, "Mercadería encontrada", renglon(macetaCentro, enCentro, "5"), renglon(coco, enCentro, "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.renglones", hasSize(2)))
                .andExpect(jsonPath("$.renglones[?(@.articuloId == %d)].cantidadResultante".formatted(macetaCentro.getId()))
                        .value(contains(5.0)));
        ajuste(dueno, "Rotura", renglon(macetaCentro, enCentro, "-2")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.renglones[0].cantidadResultante").value(3.0));

        mvc.perform(get("/api/stock/movimientos").param("articuloId", macetaCentro.getId().toString()).with(dueno))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.movimientos[*].motivo").value(contains("Rotura", "Mercadería encontrada")))
                .andExpect(jsonPath("$.movimientos[*].tipo").value(contains("ajuste", "ajuste")));

        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .containsExactly("ajustar_stock", "ajustar_stock");
        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.entidad()).isEqualTo("movimiento_stock");
        assertThat(evento.ambitosAfectados()).containsExactly(centro.getId());
        assertThat(evento.detalle().get("motivo")).isEqualTo("Mercadería encontrada");
        assertThat((List<?>) evento.detalle().get("renglones")).hasSize(2);
    }

    @Test
    void todoAjusteExigeUnMotivo() throws Exception {
        var dueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(json(post("/api/stock/ajustes"), dueno, """
                        {"renglones": [{"articuloId": %d, "ubicacionId": %d, "cantidad": 1}]}
                        """.formatted(macetaCentro.getId(), enCentro.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.motivo").exists());
        ajuste(dueno, "   ", renglon(macetaCentro, enCentro, "1")).andExpect(status().isBadRequest());
        assertThat(datos.cantidad(macetaCentro, enCentro)).isEqualByComparingTo("0");
        assertThat(eventos.stream(EventoAuditoria.class)).isEmpty();
    }

    @Test
    void unAjusteDeVariosRenglonesQueFallaEnUnoNoAplicaNinguno() throws Exception {
        datos.stock(coco, enCentro, "1");
        var dueno = datos.en(centro, Perfil.dueno_local);

        // el segundo renglón dejaría el stock negativo
        ajuste(dueno, "Recuento", renglon(macetaCentro, enCentro, "5"), renglon(coco, enCentro, "-3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(startsWith("Stock insuficiente")));
        // un renglón con un artículo que el local no ve
        ajuste(dueno, "Recuento", renglon(macetaCentro, enCentro, "5"), renglon(macetaNorte, enCentro, "1"))
                .andExpect(status().isNotFound());
        // un renglón con cantidad cero
        ajuste(dueno, "Recuento", renglon(macetaCentro, enCentro, "5"), renglon(coco, enCentro, "0"))
                .andExpect(status().isConflict());

        assertThat(datos.cantidad(macetaCentro, enCentro)).isEqualByComparingTo("0");
        assertThat(datos.cantidad(coco, enCentro)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("select count(*) from movimientos_stock where ubicacion_stock_id = ?",
                Integer.class, enCentro.getId())).isEqualTo(1);
        assertThat(eventos.stream(EventoAuditoria.class)).isEmpty();
    }

    @Test
    void loQueSeMideEnUnidadesSeAjustaEnCantidadesEnteras() throws Exception {
        ajuste(datos.en(centro, Perfil.dueno_local), "Rotura", renglon(macetaCentro, enCentro, "1.5"))
                .andExpect(status().isConflict());
    }

    @Test
    void noSeAjustaEnUnaUbicacionDesactivada() throws Exception {
        UbicacionStock vieja = datos.ubicacion(centro, "Depósito viejo");
        vieja.setActivo(false);

        ajuste(datos.en(centro, Perfil.dueno_local), "Carga", renglon(macetaCentro, vieja, "1"))
                .andExpect(status().isConflict());
    }

    // --- Consulta, límites y alertas ---

    @Test
    void elDuenoFijaMinimoYMaximoYVeLasAlertasBajoMinimo() throws Exception {
        datos.stock(coco, enFabrica, "3");
        datos.stock(turba, enFabrica, "50");
        var dueno = datos.en(fabrica, Perfil.dueno_fabrica);

        limites(dueno, coco, enFabrica, "5", "100").andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(3.0))
                .andExpect(jsonPath("$.bajoMinimo").value(true));
        limites(dueno, turba, enFabrica, "10", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.bajoMinimo").value(false));
        limites(dueno, coco, enFabrica, "10", "5").andExpect(status().isBadRequest());

        mvc.perform(get("/api/stock/alertas").with(dueno))
                .andExpect(jsonPath("$[*].articuloNombre").value(contains("Coco Elite")));
        mvc.perform(get("/api/stock").param("bajoMinimo", "true").with(dueno))
                .andExpect(jsonPath("$[*].articuloNombre").value(contains("Coco Elite")));
        mvc.perform(get("/api/stock").param("tipo", "insumo").with(dueno))
                .andExpect(jsonPath("$[*].articuloNombre").value(contains("Turba")));

        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.accion()).isEqualTo("editar_limites");
        assertThat(evento.ambitosAfectados()).containsExactly(fabrica.getId());
        assertThat(evento.detalle()).containsOnlyKeys("stockMinimo", "stockMaximo");
    }

    @Test
    void losLimitesDeUnArticuloSinMovimientosDejanSuStockEnCero() throws Exception {
        limites(datos.en(centro, Perfil.dueno_local), macetaCentro, enCentro, "2", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cantidad").value(0.0))
                .andExpect(jsonPath("$.bajoMinimo").value(true));
    }

    // --- Empleado ---

    @Test
    void elEmpleadoConsultaElStockYLosMovimientosDeSuLocalSinCostosYNoHaceAjustes() throws Exception {
        datos.costo(macetaCentro, centro, "800");
        datos.stock(macetaCentro, enCentro, "4");
        Articulo inactivo = datos.articulo(centro, TipoArticulo.reventa, "Regadera", datos.categoria("Riego"));
        datos.stock(inactivo, enCentro, "1");
        inactivo.setActivo(false);
        var empleado = datos.en(centro, Perfil.empleado_local);

        String stock = mvc.perform(get("/api/stock").with(empleado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].articuloNombre").value(contains("Maceta 5 L")))
                .andReturn().getResponse().getContentAsString();
        assertThat(claves(stock)).allSatisfy(k -> assertThat(k).containsExactlyInAnyOrder("articuloId",
                "articuloNombre", "tipo", "unidadMedida", "ubicacionId", "ubicacionNombre", "cantidad", "stockMinimo",
                "stockMaximo", "bajoMinimo"));

        String movimientos = mvc.perform(get("/api/stock/movimientos").with(empleado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(movimientos).doesNotContainIgnoringCase("costo").doesNotContainIgnoringCase("precio");

        mvc.perform(get("/api/stock/alertas").with(empleado)).andExpect(status().isOk());
        mvc.perform(get("/api/ubicaciones").with(empleado)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre").value(contains("Principal")));

        ajuste(empleado, "Rotura", renglon(macetaCentro, enCentro, "-1")).andExpect(status().isForbidden());
        limites(empleado, macetaCentro, enCentro, "1", null).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/ubicaciones"), empleado, "{\"nombre\": \"Vidriera\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ubicaciones/" + enCentro.getId() + "/desactivar").with(empleado))
                .andExpect(status().isForbidden());
        assertThat(datos.cantidad(macetaCentro, enCentro)).isEqualByComparingTo("4");
    }

    // --- Qué ve cada ámbito ---

    @Test
    void nadieVeNiTocaElStockDeOtroAmbito() throws Exception {
        datos.stock(coco, enFabrica, "10");
        datos.stock(coco, enCentro, "3");
        datos.stock(macetaNorte, enNorte, "7");

        // la fábrica no ve el stock de los locales, y cada local solo el suyo
        mvc.perform(get("/api/stock").with(datos.en(fabrica, Perfil.dueno_fabrica)))
                .andExpect(jsonPath("$[*].ubicacionId").value(contains(enFabrica.getId().intValue())));
        for (Perfil perfil : List.of(Perfil.dueno_local, Perfil.empleado_local)) {
            var centroSesion = datos.en(centro, perfil);
            mvc.perform(get("/api/stock").with(centroSesion))
                    .andExpect(jsonPath("$[*].ubicacionId").value(contains(enCentro.getId().intValue())));
            mvc.perform(get("/api/stock").param("ubicacionId", enNorte.getId().toString()).with(centroSesion))
                    .andExpect(jsonPath("$", hasSize(0)));
            mvc.perform(get("/api/stock/movimientos").param("ubicacionId", enNorte.getId().toString())
                            .with(centroSesion))
                    .andExpect(jsonPath("$.total").value(0));
            mvc.perform(get("/api/stock/movimientos").with(centroSesion))
                    .andExpect(jsonPath("$.movimientos[*].ubicacionId").value(contains(enCentro.getId().intValue())));
            mvc.perform(get("/api/ubicaciones").with(centroSesion))
                    .andExpect(jsonPath("$[*].id").value(contains(enCentro.getId().intValue())));
        }

        var duenoCentro = datos.en(centro, Perfil.dueno_local);
        ajuste(duenoCentro, "X", renglon(macetaNorte, enNorte, "-1")).andExpect(status().isNotFound());
        ajuste(duenoCentro, "X", renglon(coco, enFabrica, "-1")).andExpect(status().isNotFound());
        ajuste(duenoCentro, "X", renglon(turba, enCentro, "1")).andExpect(status().isNotFound());
        limites(duenoCentro, coco, enFabrica, "1", null).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/ubicaciones/" + enNorte.getId()), duenoCentro, "{\"nombre\": \"X\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/ubicaciones/" + enFabrica.getId() + "/desactivar").with(duenoCentro))
                .andExpect(status().isNotFound());
        assertThat(datos.cantidad(macetaNorte, enNorte)).isEqualByComparingTo("7");
        assertThat(datos.cantidad(coco, enFabrica)).isEqualByComparingTo("10");
    }

    @Test
    void elAdministradorTecnicoNoVeStock() throws Exception {
        mvc.perform(get("/api/stock").with(datos.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/stock/movimientos").with(datos.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/ubicaciones").with(datos.admin())).andExpect(status().isForbidden());
        ajuste(datos.admin(), "X", renglon(coco, enFabrica, "1")).andExpect(status().isForbidden());
    }

    @Test
    void sinSesionResponde401() throws Exception {
        mvc.perform(get("/api/stock")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ubicaciones")).andExpect(status().isUnauthorized());
    }

    // --- Ubicaciones ---

    @Test
    void elDuenoAdministraLasUbicacionesDeSuAmbitoYNoDesactivaUnaConStock() throws Exception {
        var dueno = datos.en(fabrica, Perfil.dueno_fabrica);

        String cuerpo = mvc.perform(json(post("/api/ubicaciones"), dueno, "{\"nombre\": \"Insumos\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(cuerpo).get("id").asLong();
        mvc.perform(json(post("/api/ubicaciones"), dueno, "{\"nombre\": \"INSUMOS\"}")).andExpect(status().isConflict());
        mvc.perform(json(put("/api/ubicaciones/" + id), dueno, "{\"nombre\": \"Depósito de insumos\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ubicaciones").with(dueno))
                .andExpect(jsonPath("$[*].nombre").value(contains("Depósito de insumos", "Principal")));

        datos.stock(turba, enFabrica, "1");
        mvc.perform(post("/api/ubicaciones/" + enFabrica.getId() + "/desactivar").with(dueno))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/ubicaciones/" + id + "/desactivar").with(dueno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));

        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .containsExactly("crear", "editar", "desactivar");
        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::ambitosAfectados))
                .allSatisfy(a -> assertThat(a).containsExactly(fabrica.getId()));
    }

    // --- Consistencia ---

    @Test
    void paraCadaArticuloYUbicacionLaCantidadActualEsLaSumaDeLosMovimientos() throws Exception {
        UbicacionStock deposito = datos.ubicacion(centro, "Depósito");
        var duenoCentro = datos.en(centro, Perfil.dueno_local);
        var duenoFabrica = datos.en(fabrica, Perfil.dueno_fabrica);
        ajuste(duenoCentro, "Carga", renglon(macetaCentro, enCentro, "10"), renglon(macetaCentro, deposito, "4"),
                renglon(coco, enCentro, "6")).andExpect(status().isCreated());
        ajuste(duenoCentro, "Rotura", renglon(macetaCentro, enCentro, "-3"), renglon(coco, enCentro, "-6"))
                .andExpect(status().isCreated());
        ajuste(duenoCentro, "Rotura", renglon(macetaCentro, deposito, "-5")).andExpect(status().isConflict());
        ajuste(duenoFabrica, "Carga", renglon(turba, enFabrica, "100"), renglon(coco, enFabrica, "20"))
                .andExpect(status().isCreated());
        ajuste(duenoFabrica, "Uso", renglon(turba, enFabrica, "-40")).andExpect(status().isCreated());
        limites(duenoFabrica, coco, enFabrica, "5", "50").andExpect(status().isOk());
        limites(duenoCentro, macetaCentro, datos.ubicacion(centro, "Vidriera"), "1", null)
                .andExpect(status().isOk());

        assertThat(datos.cantidad(macetaCentro, enCentro)).isEqualByComparingTo("7");
        assertThat(datos.cantidad(macetaCentro, deposito)).isEqualByComparingTo("4");
        assertThat(datos.cantidad(coco, enCentro)).isEqualByComparingTo("0");
        assertThat(datos.cantidad(turba, enFabrica)).isEqualByComparingTo("60");
        // en toda la base: ningún artículo y ubicación con una cantidad distinta de la suma de sus movimientos
        assertThat(jdbc.queryForList("""
                select coalesce(s.articulo_id, m.articulo_id) as articulo,
                       coalesce(s.ubicacion_stock_id, m.ubicacion_stock_id) as ubicacion
                from stock_articulos s
                full join (select articulo_id, ubicacion_stock_id, sum(cantidad) as suma
                           from movimientos_stock group by articulo_id, ubicacion_stock_id) m
                  on m.articulo_id = s.articulo_id and m.ubicacion_stock_id = s.ubicacion_stock_id
                where coalesce(s.cantidad_actual, 0) <> coalesce(m.suma, 0)
                """)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from stock_articulos where ubicacion_stock_id in (?, ?)",
                Integer.class, enCentro.getId(), deposito.getId())).isEqualTo(3);
    }

    // --- Ayudas ---

    private ResultActions ajuste(RequestPostProcessor sesion, String motivo, String... renglones) throws Exception {
        return mvc.perform(json(post("/api/stock/ajustes"), sesion, """
                {"motivo": "%s", "renglones": [%s]}
                """.formatted(motivo, String.join(", ", renglones))));
    }

    private static String renglon(Articulo articulo, UbicacionStock ubicacion, String cantidad) {
        return "{\"articuloId\": %d, \"ubicacionId\": %d, \"cantidad\": %s}".formatted(articulo.getId(),
                ubicacion.getId(), cantidad);
    }

    private ResultActions limites(RequestPostProcessor sesion, Articulo articulo, UbicacionStock ubicacion,
            String minimo, String maximo) throws Exception {
        return mvc.perform(json(put("/api/stock/limites"), sesion, """
                {"articuloId": %d, "ubicacionId": %d, "stockMinimo": %s, "stockMaximo": %s}
                """.formatted(articulo.getId(), ubicacion.getId(), minimo, maximo)));
    }

    private List<Set<String>> claves(String json) throws Exception {
        List<Map<String, Object>> lista = mapper.readValue(json, mapper.getTypeFactory()
                .constructCollectionType(List.class, Map.class));
        return lista.stream().map(Map::keySet).toList();
    }
}
