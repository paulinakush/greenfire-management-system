package com.greenfire.ambitos;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.seguridad.Perfil;

/** El local central: a lo sumo uno, siempre un local activo y visible, y solo lo cambia el administrador técnico. */
@PruebaIntegracion
class LocalCentralTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AmbitoRepository ambitos;
    @Autowired
    ApplicationEvents eventos;

    Ambito centro;
    Ambito norte;

    @BeforeEach
    void datos() {
        centro = visible(datos.local("Centro"));
        norte = visible(datos.local("Norte"));
    }

    // --- Base de datos ---

    @Test
    void laBaseNoAdmiteDosLocalesCentrales() {
        jdbc.update("update ambitos set es_central = true where id = ?", centro.getId());

        rechaza(() -> jdbc.update("update ambitos set es_central = true where id = ?", norte.getId()),
                "uq_ambitos_central");
    }

    @Test
    void laBaseExigeQueElCentralSeaUnLocalActivoYVisible() {
        Ambito oculto = datos.local("Oculto");
        Ambito fabrica = datos.fabrica();
        ambitos.flush();

        rechaza(() -> jdbc.update("update ambitos set es_central = true where id = ?", oculto.getId()),
                "ambitos_central_valido");
        rechaza(() -> jdbc.update("update ambitos set es_central = true where id = ?", fabrica.getId()),
                "ambitos_central_valido");

        jdbc.update("update ambitos set es_central = true where id = ?", centro.getId());
        rechaza(() -> jdbc.update("update ambitos set visible_web = false where id = ?", centro.getId()),
                "ambitos_central_valido");
        rechaza(() -> jdbc.update("update ambitos set activo = false where id = ?", centro.getId()),
                "ambitos_central_valido");
    }

    // --- API ---

    @Test
    void elAdminMarcaElCentralYDesmarcaAlAnteriorAuditandoALosDos() throws Exception {
        mvc.perform(post("/api/admin/ambitos/" + centro.getId() + "/central").with(datos.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.esCentral").value(true));
        eventos.clear();

        mvc.perform(post("/api/admin/ambitos/" + norte.getId() + "/central").with(datos.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.esCentral").value(true));

        assertThat(jdbc.queryForList("select id from ambitos where es_central", Long.class))
                .containsExactly(norte.getId());
        List<EventoAuditoria> registrados = eventos.stream(EventoAuditoria.class).toList();
        assertThat(registrados).extracting(EventoAuditoria::accion)
                .containsExactly("desmarcar_central", "marcar_central");
        assertThat(registrados.get(0).ambitosAfectados()).containsExactly(centro.getId());
        assertThat(registrados.get(0).detalle().get("esCentral")).isEqualTo(Map.of("antes", true, "despues", false));
        assertThat(registrados.get(1).ambitosAfectados()).containsExactly(norte.getId());
        assertThat(registrados.get(1).detalle().get("esCentral")).isEqualTo(Map.of("antes", false, "despues", true));

        mvc.perform(get("/api/publico/locales"))
                .andExpect(jsonPath("$[?(@.nombre == 'Norte')].esCentral").value(true))
                .andExpect(jsonPath("$[?(@.nombre == 'Centro')].esCentral").value(false));
    }

    @Test
    void soloElAdminCambiaElCentral() throws Exception {
        mvc.perform(post("/api/admin/ambitos/" + centro.getId() + "/central")
                        .with(datos.en(centro, Perfil.dueno_local)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/ambitos/" + centro.getId() + "/central")
                        .with(datos.en(datos.fabrica(), Perfil.dueno_fabrica)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/ambitos/" + centro.getId() + "/central").with(csrf()))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForList("select id from ambitos where es_central", Long.class)).isEmpty();
    }

    @Test
    void noSeMarcaComoCentralUnLocalOcultoNiLaFabrica() throws Exception {
        Ambito oculto = datos.local("Oculto");
        Ambito fabrica = datos.fabrica();

        mvc.perform(post("/api/admin/ambitos/" + oculto.getId() + "/central").with(datos.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(
                        "El local central tiene que ser un local activo y visible en la web."));
        mvc.perform(post("/api/admin/ambitos/" + fabrica.getId() + "/central").with(datos.admin()))
                .andExpect(status().isConflict());
    }

    @Test
    void elCentralNoSeOcultaNiSeDesactivaSinMarcarAntesOtro() throws Exception {
        mvc.perform(post("/api/admin/ambitos/" + centro.getId() + "/central").with(datos.admin()))
                .andExpect(status().isOk());

        mvc.perform(json(put("/api/admin/ambitos/" + centro.getId()), datos.admin(), """
                        {"nombre": "Centro", "visibleWeb": false}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(
                        "Es el local central: para ocultarlo de la web, primero marcá otro local como central."));
        mvc.perform(post("/api/admin/ambitos/" + centro.getId() + "/desactivar").with(datos.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(
                        "Es el local central: para desactivarlo, primero marcá otro local como central."));

        mvc.perform(post("/api/admin/ambitos/" + norte.getId() + "/central").with(datos.admin()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/ambitos/" + centro.getId() + "/desactivar").with(datos.admin()))
                .andExpect(status().isOk());
    }

    private Ambito visible(Ambito local) {
        local.setVisibleWeb(true);
        return ambitos.saveAndFlush(local);
    }

    /** Cada intento corre en un savepoint, así un rechazo no aborta la transacción del test. */
    private void rechaza(Runnable operacion, String restriccion) {
        jdbc.execute("savepoint intento");
        try {
            assertThatThrownBy(operacion::run).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(restriccion);
        } finally {
            jdbc.execute("rollback to savepoint intento");
        }
    }
}
