package com.greenfire.catalogo;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.seguridad.Perfil;

@PruebaIntegracion
class ArticuloApiTest {

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
    Articulo turba;
    Articulo cocoElite;
    Articulo macetaCentro;
    Articulo macetaNorte;

    @BeforeEach
    void catalogo() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        sustratos = datos.categoria("Sustratos");
        turba = datos.articulo(fabrica, TipoArticulo.insumo, "Turba", null);
        cocoElite = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco Elite", sustratos);
        macetaCentro = datos.articulo(centro, TipoArticulo.reventa, "Maceta 5 L", sustratos);
        macetaNorte = datos.articulo(norte, TipoArticulo.reventa, "Maceta 10 L", sustratos);
    }

    // --- Qué ve cada ámbito ---

    @Test
    void unDuenoDeLocalVeLosProductosDeLaFabricaYSuReventaPeroNoLaReventaDeOtroLocal() throws Exception {
        var dueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(get("/api/articulos").with(dueno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre").value(containsInAnyOrder("Coco Elite", "Maceta 5 L")));
        mvc.perform(get("/api/articulos/" + cocoElite.getId()).with(dueno)).andExpect(status().isOk());
        mvc.perform(get("/api/articulos/" + macetaCentro.getId()).with(dueno)).andExpect(status().isOk());
        mvc.perform(get("/api/articulos/" + macetaNorte.getId()).with(dueno)).andExpect(status().isNotFound());
        mvc.perform(get("/api/articulos/" + turba.getId()).with(dueno)).andExpect(status().isNotFound());
    }

    @Test
    void laFabricaVeSusInsumosYProductosPeroNoLaReventaDeLosLocales() throws Exception {
        var dueno = datos.en(fabrica, Perfil.dueno_fabrica);

        mvc.perform(get("/api/articulos").with(dueno))
                .andExpect(jsonPath("$[*].nombre").value(containsInAnyOrder("Turba", "Coco Elite")));
        mvc.perform(get("/api/articulos/" + macetaCentro.getId()).with(dueno)).andExpect(status().isNotFound());
    }

    @Test
    void losFiltrosNoAmplianLoQueVeElAmbito() throws Exception {
        mvc.perform(get("/api/articulos").param("tipo", "reventa").with(datos.en(centro, Perfil.dueno_local)))
                .andExpect(jsonPath("$[*].nombre").value(containsInAnyOrder("Maceta 5 L")));
        mvc.perform(get("/api/articulos").param("q", "maceta").with(datos.en(fabrica, Perfil.dueno_fabrica)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- Alta y edición ---

    @Test
    void laFabricaCreaInsumosYProductosTerminadosYQuedaEnAuditoriaConSuAmbito() throws Exception {
        var dueno = datos.en(fabrica, Perfil.dueno_fabrica);

        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "insumo", "nombre": "Perlita", "unidadMedida": "kg", "alicuotaIva": 21}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.costo").value(nullValue()));
        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "producto_terminado", "nombre": "Huertero Plus", "categoriaId": %d,
                 "unidadMedida": "unidad", "alicuotaIva": 10.5, "visibleWeb": true,
                 "imagen": "assets/productos/01 - Huertero plus.png"}
                """.formatted(sustratos.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoriaNombre").value(sustratos.getNombre()));

        EventoAuditoria alta = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(alta.accion()).isEqualTo("crear");
        assertThat(alta.entidad()).isEqualTo("articulo");
        assertThat(alta.ambitosAfectados()).containsExactly(fabrica.getId());
    }

    @Test
    void laReventaEsDeLosLocalesYLosInsumosYProductosDeLaFabrica() throws Exception {
        String reventa = """
                {"tipo": "reventa", "nombre": "Tijera", "categoriaId": %d, "unidadMedida": "unidad", "alicuotaIva": 21}
                """.formatted(sustratos.getId());
        String producto = """
                {"tipo": "producto_terminado", "nombre": "Mix", "categoriaId": %d, "unidadMedida": "unidad",
                 "alicuotaIva": 21}
                """.formatted(sustratos.getId());

        mvc.perform(json(post("/api/articulos"), datos.en(centro, Perfil.dueno_local), reventa))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.editable").value(true));
        mvc.perform(json(post("/api/articulos"), datos.en(fabrica, Perfil.dueno_fabrica), reventa))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/articulos"), datos.en(centro, Perfil.dueno_local), producto))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/articulos"), datos.en(centro, Perfil.empleado_local), reventa))
                .andExpect(status().isForbidden());
    }

    @Test
    void unDuenoDeLocalNoEditaLosProductosDeLaFabricaNiLaReventaDeOtroLocal() throws Exception {
        var dueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(json(put("/api/articulos/" + cocoElite.getId()), dueno, edicion("Coco", sustratos)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/articulos/" + cocoElite.getId() + "/desactivar").with(dueno))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/articulos/" + macetaNorte.getId()), dueno, edicion("Maceta", sustratos)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/articulos/" + macetaNorte.getId() + "/desactivar").with(dueno))
                .andExpect(status().isNotFound());
    }

    @Test
    void laEdicionAuditaSoloLosCamposQueCambiaronConElAmbitoAfectado() throws Exception {
        mvc.perform(json(put("/api/articulos/" + macetaCentro.getId()), datos.en(centro, Perfil.dueno_local),
                        edicion("Maceta 5 litros", sustratos)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Maceta 5 litros"));

        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.accion()).isEqualTo("editar");
        assertThat(evento.ambitosAfectados()).containsExactly(centro.getId());
        assertThat(evento.detalle()).containsOnlyKeys("nombre");
        assertThat(evento.detalle().get("nombre")).isEqualTo(Map.of("antes", "Maceta 5 L", "despues", "Maceta 5 litros"));
    }

    @Test
    void elNombreEsUnicoEnCadaAmbitoPeroSePuedeRepetirEnOtro() throws Exception {
        String maceta = """
                {"tipo": "reventa", "nombre": "MACETA 10 L", "categoriaId": %d, "unidadMedida": "unidad",
                 "alicuotaIva": 21}
                """.formatted(sustratos.getId());

        mvc.perform(json(post("/api/articulos"), datos.en(norte, Perfil.dueno_local), maceta))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/articulos"), datos.en(centro, Perfil.dueno_local), maceta))
                .andExpect(status().isCreated());
    }

    @Test
    void validaCategoriaAlicuotaEImagen() throws Exception {
        var dueno = datos.en(fabrica, Perfil.dueno_fabrica);
        Categoria inactiva = datos.categoria("Vieja");
        inactiva.setActivo(false);

        // los insumos no llevan categoría ni se muestran en la web
        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "insumo", "nombre": "Humus", "categoriaId": %d, "unidadMedida": "kg", "alicuotaIva": 21}
                """.formatted(sustratos.getId()))).andExpect(status().isConflict());
        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "insumo", "nombre": "Humus", "unidadMedida": "kg", "alicuotaIva": 21, "visibleWeb": true}
                """)).andExpect(status().isConflict());
        // los productos terminados llevan una categoría activa
        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "producto_terminado", "nombre": "Mix", "unidadMedida": "unidad", "alicuotaIva": 21}
                """)).andExpect(status().isConflict());
        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "producto_terminado", "nombre": "Mix", "categoriaId": %d, "unidadMedida": "unidad",
                 "alicuotaIva": 21}
                """.formatted(inactiva.getId()))).andExpect(status().isConflict());
        // alícuota, unidad e imagen
        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "insumo", "nombre": "Humus", "unidadMedida": "kg", "alicuotaIva": 15}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.alicuotaIvaValida").exists());
        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "insumo", "nombre": "Humus", "unidadMedida": "tonelada", "alicuotaIva": 21}
                """)).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/articulos"), dueno, """
                {"tipo": "insumo", "nombre": "Humus", "unidadMedida": "kg", "alicuotaIva": 21,
                 "imagen": "assets/productos/../../.env"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos.imagen").exists());
    }

    // --- Costos ---

    @Test
    void cadaAmbitoCargaYVeSoloSuCosto() throws Exception {
        var fabricaDueno = datos.en(fabrica, Perfil.dueno_fabrica);
        var centroDueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(json(put("/api/articulos/" + cocoElite.getId() + "/costo"), fabricaDueno, "{\"costo\": 1500.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costo").value(1500.50));
        mvc.perform(json(put("/api/articulos/" + macetaCentro.getId() + "/costo"), centroDueno, "{\"costo\": 800}"))
                .andExpect(status().isOk());

        // el local no ve el costo de la fábrica
        mvc.perform(get("/api/articulos/" + cocoElite.getId()).with(centroDueno))
                .andExpect(jsonPath("$.costo").value(nullValue()));
        mvc.perform(get("/api/articulos/" + macetaCentro.getId()).with(centroDueno))
                .andExpect(jsonPath("$.costo").value(800.0));
        mvc.perform(get("/api/articulos/" + cocoElite.getId()).with(fabricaDueno))
                .andExpect(jsonPath("$.costo").value(1500.50));

        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.accion()).isEqualTo("editar_costo");
        assertThat(evento.ambitosAfectados()).containsExactly(fabrica.getId());
        Map<String, Object> costo = new HashMap<>();
        costo.put("antes", null);
        costo.put("despues", new BigDecimal("1500.50"));
        assertThat(evento.detalle().get("costo")).isEqualTo(costo);
    }

    @Test
    void elCostoDeUnProductoDeLaFabricaEnElLocalNoSeCargaAMano() throws Exception {
        mvc.perform(json(put("/api/articulos/" + cocoElite.getId() + "/costo"), datos.en(centro, Perfil.dueno_local),
                        "{\"costo\": 100}"))
                .andExpect(status().isConflict());
        mvc.perform(json(put("/api/articulos/" + macetaNorte.getId() + "/costo"), datos.en(centro, Perfil.dueno_local),
                        "{\"costo\": 100}"))
                .andExpect(status().isNotFound());
        mvc.perform(json(put("/api/articulos/" + macetaCentro.getId() + "/costo"),
                        datos.en(centro, Perfil.empleado_local), "{\"costo\": 100}"))
                .andExpect(status().isForbidden());
    }

    // --- Empleado ---

    @Test
    void elEmpleadoVeLosArticulosActivosDeSuLocalSinCostos() throws Exception {
        datos.costo(macetaCentro, centro, "800");
        Articulo inactivo = datos.articulo(centro, TipoArticulo.reventa, "Regadera", sustratos);
        inactivo.setActivo(false);
        var empleado = datos.en(centro, Perfil.empleado_local);

        String cuerpo = mvc.perform(get("/api/articulos").param("activo", "false").with(empleado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre").value(containsInAnyOrder("Coco Elite", "Maceta 5 L")))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> lista = mapper.readValue(cuerpo, mapper.getTypeFactory()
                .constructCollectionType(List.class, Map.class));
        assertThat(lista).allSatisfy(a -> assertThat(a.keySet()).containsExactlyInAnyOrder("id", "tipo", "nombre",
                "descripcion", "categoriaId", "categoriaNombre", "marca", "unidadMedida", "imagen", "alicuotaIva"));

        mvc.perform(get("/api/articulos/" + macetaCentro.getId()).with(empleado))
                .andExpect(jsonPath("$.costo").doesNotExist());
        mvc.perform(get("/api/articulos/" + inactivo.getId()).with(empleado)).andExpect(status().isNotFound());
        mvc.perform(get("/api/articulos/" + macetaNorte.getId()).with(empleado)).andExpect(status().isNotFound());
    }

    private static String edicion(String nombre, Categoria categoria) {
        return """
                {"nombre": "%s", "categoriaId": %d, "unidadMedida": "unidad", "alicuotaIva": 21}
                """.formatted(nombre, categoria.getId());
    }
}
