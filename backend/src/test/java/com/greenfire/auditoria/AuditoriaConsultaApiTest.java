package com.greenfire.auditoria;

import static com.greenfire.Datos.json;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.usuarios.Usuario;

@PruebaIntegracion
class AuditoriaConsultaApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;

    Ambito centro;
    Ambito norte;
    Usuario duenoCentro;
    RequestPostProcessor comoDuenoCentro;
    RequestPostProcessor comoDuenoNorte;

    /** Una edición en cada local y un login fallido. */
    @BeforeEach
    void operaciones() throws Exception {
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        duenoCentro = datos.usuario("Lucia");
        datos.perfil(duenoCentro, centro, Perfil.dueno_local);
        comoDuenoCentro = datos.sesion(Sesion.enAmbito(duenoCentro.getId(), centro.getId(), Perfil.dueno_local));
        comoDuenoNorte = datos.en(norte, Perfil.dueno_local);

        mvc.perform(json(put("/api/ambito/contacto"), comoDuenoCentro, "{\"horarios\": \"9 a 18\"}"))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/ambito/contacto"), comoDuenoNorte, "{\"horarios\": \"10 a 20\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"nadie@greenfire.test\", \"password\": \"cualquier-clave\"}"));
    }

    @Test
    void unDuenoDeLocalVeSoloLaAuditoriaDeSuLocal() throws Exception {
        mvc.perform(get("/api/auditoria").with(comoDuenoCentro))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registros", hasSize(1)))
                .andExpect(jsonPath("$.registros[*].ambitoId", everyItem(is(centro.getId().intValue()))))
                .andExpect(jsonPath("$.registros[0].accion").value("editar_contacto"))
                .andExpect(jsonPath("$.registros[0].detalle.horarios.despues").value("9 a 18"));
    }

    @Test
    void unDuenoNoPuedePedirOtroAmbitoPorParametro() throws Exception {
        mvc.perform(get("/api/auditoria").param("ambitoId", norte.getId().toString()).with(comoDuenoCentro))
                .andExpect(jsonPath("$.registros[*].ambitoId", everyItem(is(centro.getId().intValue()))));
    }

    @Test
    void unDuenoNoVeLosLoginsFallidos() throws Exception {
        mvc.perform(get("/api/auditoria").param("accion", "login_fallido").with(comoDuenoCentro))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void unEmpleadoRecibe403() throws Exception {
        mvc.perform(get("/api/auditoria").with(datos.en(centro, Perfil.empleado_local)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinSesionRecibe401() throws Exception {
        mvc.perform(get("/api/auditoria")).andExpect(status().isUnauthorized());
    }

    @Test
    void elAdministradorTecnicoVeTodoIncluidosLosLoginsFallidos() throws Exception {
        mvc.perform(get("/api/auditoria").with(datos.admin()))
                .andExpect(jsonPath("$.registros[*].ambitoId", hasItem(centro.getId().intValue())))
                .andExpect(jsonPath("$.registros[*].ambitoId", hasItem(norte.getId().intValue())))
                .andExpect(jsonPath("$.registros[*].emailIntentado", hasItem("nadie@greenfire.test")));
    }

    @Test
    void filtraPorAccionUsuarioYFecha() throws Exception {
        RequestPostProcessor admin = datos.admin();
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires"));

        mvc.perform(get("/api/auditoria").param("accion", "login_fallido").with(admin))
                .andExpect(jsonPath("$.registros[*].accion", everyItem(is("login_fallido"))))
                .andExpect(jsonPath("$.registros[*].emailIntentado", hasItem("nadie@greenfire.test")));
        mvc.perform(get("/api/auditoria").param("usuarioId", duenoCentro.getId().toString()).with(admin))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.registros[0].usuarioNombre").value("Lucia"));
        mvc.perform(get("/api/auditoria").param("desde", hoy.toString()).param("hasta", hoy.toString())
                        .param("usuarioId", duenoCentro.getId().toString()).with(admin))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/auditoria").param("desde", hoy.plusDays(1).toString())
                        .param("usuarioId", duenoCentro.getId().toString()).with(admin))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/auditoria").param("hasta", hoy.minusDays(1).toString())
                        .param("usuarioId", duenoCentro.getId().toString()).with(admin))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void paginaLosResultadosDelMasNuevoAlMasViejo() throws Exception {
        mvc.perform(json(put("/api/ambito/contacto"), comoDuenoCentro, "{\"horarios\": \"8 a 16\"}"));

        mvc.perform(get("/api/auditoria").param("tamano", "1").with(comoDuenoCentro))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.registros", hasSize(1)))
                .andExpect(jsonPath("$.registros[0].detalle.horarios.despues").value("8 a 16"));
        mvc.perform(get("/api/auditoria").param("tamano", "1").param("pagina", "1").with(comoDuenoCentro))
                .andExpect(jsonPath("$.registros[0].detalle.horarios.despues").value("9 a 18"));
        mvc.perform(get("/api/auditoria").param("tamano", "500").with(comoDuenoCentro))
                .andExpect(jsonPath("$.tamano").value(AuditoriaService.TAMANO_MAXIMO));
    }

    @Test
    void unaFechaMalFormadaResponde400() throws Exception {
        mvc.perform(get("/api/auditoria").param("desde", "29/10/2026").with(comoDuenoCentro))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje", not(is(""))));
    }
}
