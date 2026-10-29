package com.greenfire.ambitos;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.EntidadFiscal;
import com.greenfire.seguridad.Perfil;

@PruebaIntegracion
class AmbitoApiTest {

    private static final String CONTACTO = """
            {"direccion": "Av. Siempre Viva 742", "horarios": "Lun a Sáb 10 a 20",
             "telefono": "351 555-0000", "email": "centro@greenfire.com"}
            """;

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;
    @Autowired
    AmbitoRepository ambitos;

    // --- Administrador técnico ---

    @Test
    void elAdminCreaLaFabricaYNoPuedeCrearUnaSegunda() throws Exception {
        EntidadFiscal entidad = datos.entidad(CondicionIva.responsable_inscripto);
        String fabrica = """
                {"tipo": "fabrica", "nombre": "Fábrica", "entidadFiscalId": %d}
                """.formatted(entidad.getId());

        mvc.perform(json(post("/api/admin/ambitos"), datos.admin(), fabrica))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("fabrica"));
        mvc.perform(json(post("/api/admin/ambitos"), datos.admin(), fabrica))
                .andExpect(status().isConflict());
    }

    @Test
    void laFabricaNoPuedeMostrarseEnLaWeb() throws Exception {
        EntidadFiscal entidad = datos.entidad(CondicionIva.responsable_inscripto);

        mvc.perform(json(post("/api/admin/ambitos"), datos.admin(), """
                {"tipo": "fabrica", "nombre": "Fábrica", "entidadFiscalId": %d, "visibleWeb": true}
                """.formatted(entidad.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void elAdminCambiaElNombreYLaVisibilidadWebYQuedaEnAuditoria() throws Exception {
        Ambito local = datos.local("Centro");

        mvc.perform(json(put("/api/admin/ambitos/" + local.getId()), datos.admin(), """
                {"nombre": "Local Centro", "visibleWeb": true}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Local Centro"));

        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.detalle()).containsOnlyKeys("nombre", "visibleWeb");
        assertThat(evento.detalle().get("nombre")).isEqualTo(Map.of("antes", "Centro", "despues", "Local Centro"));
    }

    @Test
    void ningunPerfilDeAmbitoEntraALaAdministracionDeAmbitos() throws Exception {
        Ambito local = datos.local("Centro");

        for (Perfil perfil : Perfil.values()) {
            mvc.perform(get("/api/admin/ambitos").with(datos.en(local, perfil)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Dueño de local ---

    @Test
    void elDuenoDeLocalEditaElContactoDeSuLocalYQuedaEnAuditoria() throws Exception {
        Ambito local = datos.local("Centro");

        mvc.perform(json(put("/api/ambito/contacto"), datos.en(local, Perfil.dueno_local), CONTACTO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(local.getId()))
                .andExpect(jsonPath("$.horarios").value("Lun a Sáb 10 a 20"));

        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.accion()).isEqualTo("editar_contacto");
        assertThat(evento.entidadId()).isEqualTo(local.getId());
        assertThat(evento.detalle()).containsOnlyKeys("direccion", "horarios", "telefono", "email");
        Map<String, Object> direccion = new HashMap<>();
        direccion.put("antes", null);
        direccion.put("despues", "Av. Siempre Viva 742");
        assertThat(evento.detalle().get("direccion")).isEqualTo(direccion);
    }

    @Test
    void elDuenoDeLocalNoPuedeCambiarElNombreNiLaVisibilidadWeb() throws Exception {
        Ambito local = datos.local("Centro");
        RequestPostProcessor dueno = datos.en(local, Perfil.dueno_local);

        mvc.perform(json(put("/api/ambito/contacto"), dueno, """
                {"nombre": "Otro nombre", "visibleWeb": true}
                """))
                .andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/admin/ambitos/" + local.getId()), dueno, """
                {"nombre": "Otro nombre", "visibleWeb": true}
                """))
                .andExpect(status().isForbidden());

        assertThat(ambitos.findById(local.getId()).orElseThrow().getNombre()).isEqualTo("Centro");
    }

    @Test
    void elEmpleadoYElDuenoDeFabricaNoEditanElContacto() throws Exception {
        Ambito local = datos.local("Centro");
        Ambito fabrica = datos.ambito(TipoAmbito.fabrica, "Fábrica", CondicionIva.responsable_inscripto);

        mvc.perform(json(put("/api/ambito/contacto"), datos.en(local, Perfil.empleado_local), CONTACTO))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/ambito/contacto"), datos.en(fabrica, Perfil.dueno_fabrica), CONTACTO))
                .andExpect(status().isForbidden());
    }

    @Test
    void cadaUsuarioVeSoloElAmbitoEnElQueEstaTrabajando() throws Exception {
        Ambito centro = datos.local("Centro");
        datos.local("Norte");

        mvc.perform(get("/api/ambito").with(datos.en(centro, Perfil.empleado_local)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Centro"));
        mvc.perform(get("/api/ambito").with(datos.admin()))
                .andExpect(status().isForbidden());
    }

    // --- Sitio público ---

    @Test
    void elSitioPublicoMuestraSoloLosLocalesVisiblesYActivos() throws Exception {
        Ambito visible = datos.local("Centro");
        visible.setVisibleWeb(true);
        Ambito inactivo = datos.local("Cerrado");
        inactivo.setVisibleWeb(true);
        inactivo.setActivo(false);
        datos.local("Oculto");

        mvc.perform(get("/api/publico/locales"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre").value(contains("Centro")));
    }

    @Test
    void elSitioPublicoMuestraSoloDatosDeContactoSinNadaFiscal() throws Exception {
        Ambito visible = datos.local("Centro");
        visible.setVisibleWeb(true);
        visible.setDireccion("Av. Colón 100");

        String json = mvc.perform(get("/api/publico/locales"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> local = new ObjectMapper().readValue(json, List.class).get(0) instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        assertThat(local.keySet()).containsExactlyInAnyOrder("nombre", "direccion", "horarios", "telefono", "email");
    }
}
