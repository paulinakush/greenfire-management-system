package com.greenfire.catalogo;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.seguridad.Perfil;

@PruebaIntegracion
class CategoriaApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;

    @Test
    void elDuenoDeFabricaCreaEditaYDesactivaUnaCategoriaYQuedaEnAuditoria() throws Exception {
        var dueno = datos.en(datos.fabrica(), Perfil.dueno_fabrica);

        String id = mvc.perform(json(post("/api/categorias"), dueno, "{\"nombre\": \"Sustratos\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activo").value(true))
                .andReturn().getResponse().getContentAsString().replaceAll("^\\{\"id\":(\\d+).*", "$1");
        mvc.perform(json(put("/api/categorias/" + id), dueno, "{\"nombre\": \"Sustratos premium\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Sustratos premium"));
        mvc.perform(post("/api/categorias/" + id + "/desactivar").with(dueno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));

        assertThat(eventos.stream(EventoAuditoria.class).map(EventoAuditoria::accion))
                .containsExactly("crear", "editar", "desactivar");
        EventoAuditoria edicion = eventos.stream(EventoAuditoria.class).skip(1).findFirst().orElseThrow();
        assertThat(edicion.detalle()).containsOnlyKeys("nombre");
        assertThat(edicion.detalle().get("nombre"))
                .isEqualTo(Map.of("antes", "Sustratos", "despues", "Sustratos premium"));
    }

    @Test
    void elNombreDeLaCategoriaEsUnicoSinImportarMayusculas() throws Exception {
        var dueno = datos.en(datos.fabrica(), Perfil.dueno_fabrica);
        mvc.perform(json(post("/api/categorias"), dueno, "{\"nombre\": \"Macetas\"}"))
                .andExpect(status().isCreated());

        mvc.perform(json(post("/api/categorias"), dueno, "{\"nombre\": \"MACETAS\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void losLocalesConsultanLasCategoriasPeroNoLasAdministran() throws Exception {
        Ambito local = datos.local("Centro");
        Categoria categoria = datos.categoria("Fertilizantes");

        for (Perfil perfil : new Perfil[] {Perfil.dueno_local, Perfil.empleado_local}) {
            mvc.perform(get("/api/categorias").with(datos.en(local, perfil)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].nombre").value(hasItem(categoria.getNombre())));
            mvc.perform(json(post("/api/categorias"), datos.en(local, perfil), "{\"nombre\": \"Otra\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/categorias/" + categoria.getId() + "/desactivar").with(datos.en(local, perfil)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void elEmpleadoVeSoloLasCategoriasActivas() throws Exception {
        Ambito local = datos.local("Centro");
        Categoria inactiva = datos.categoria("Vieja");
        inactiva.setActivo(false);

        mvc.perform(get("/api/categorias").with(datos.en(local, Perfil.empleado_local)))
                .andExpect(jsonPath("$[*].nombre").value(not(hasItem(inactiva.getNombre()))));
        mvc.perform(get("/api/categorias").with(datos.en(local, Perfil.dueno_local)))
                .andExpect(jsonPath("$[*].nombre").value(hasItem(inactiva.getNombre())));
    }

    @Test
    void elAdministradorTecnicoNoVeElCatalogo() throws Exception {
        mvc.perform(get("/api/categorias").with(datos.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/articulos").with(datos.admin())).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/categorias"), datos.admin(), "{\"nombre\": \"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinSesionResponde401() throws Exception {
        mvc.perform(get("/api/categorias")).andExpect(status().isUnauthorized());
    }
}
