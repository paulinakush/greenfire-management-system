package com.greenfire.auditoria;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.seguridad.Perfil;
import com.greenfire.usuarios.Usuario;

/** Qué se guarda en la tabla auditorias y que no se pueda modificar. */
@PruebaIntegracion
class AuditoriaRegistroTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void unaEdicionQuedaConUsuarioPerfilAmbitoAccionEntidadFechaYValoresAnteriorYNuevo() throws Exception {
        Ambito centro = datos.local("Centro");
        Usuario lucia = datos.usuario("Lucia");
        datos.perfil(lucia, centro, Perfil.dueno_local);
        RequestPostProcessor sesion = datos.sesion(com.greenfire.seguridad.Sesion.enAmbito(lucia.getId(),
                centro.getId(), Perfil.dueno_local));

        mvc.perform(json(put("/api/ambito/contacto"), sesion, "{\"horarios\": \"Lun a Vie 9 a 18\"}"))
                .andExpect(status().isOk());

        Map<String, Object> fila = jdbc.queryForMap("select * from auditorias where accion = 'editar_contacto'");
        assertThat(fila.get("usuario_id")).isEqualTo(lucia.getId());
        assertThat(fila.get("perfil")).isEqualTo("dueno_local");
        assertThat(fila.get("ambito_id")).isEqualTo(centro.getId());
        assertThat(fila.get("entidad")).isEqualTo("ambito");
        assertThat(fila.get("entidad_id")).isEqualTo(centro.getId());
        assertThat(fila.get("ip")).isEqualTo("127.0.0.1");
        assertThat(fila.get("fecha_hora")).isNotNull();
        assertThat(fila.get("detalle")).isEqualTo("{\"horarios\":{\"antes\":null,\"despues\":\"Lun a Vie 9 a 18\"}}");
    }

    @Test
    void unLoginFallidoConUnEmailInexistenteQuedaConEmailEIpYSinUsuario() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"nadie@greenfire.test\", \"password\": \"cualquier-clave\"}"))
                .andExpect(status().isUnauthorized());

        Map<String, Object> fila = jdbc.queryForMap("select * from auditorias where accion = 'login_fallido'");
        assertThat(fila.get("usuario_id")).isNull();
        assertThat(fila.get("email_intentado")).isEqualTo("nadie@greenfire.test");
        assertThat(fila.get("ip")).isEqualTo("127.0.0.1");
        assertThat((String) fila.get("detalle")).doesNotContain("cualquier-clave");
    }

    @Test
    void nuncaSeGuardanContrasenasHashesTokensNiElCertificadoDeArca() throws Exception {
        Ambito centro = datos.local("Centro");
        // Alta de empleado y restablecimiento de contraseña.
        mvc.perform(json(post("/api/usuarios"), datos.en(centro, Perfil.dueno_local), """
                {"nombre": "Pedro", "email": "pedro@greenfire.test", "password": "clave-de-pedro-1"}
                """)).andExpect(status().isCreated());
        Usuario lucia = datos.usuario("Lucia");
        datos.perfil(lucia, centro, Perfil.dueno_local);
        mvc.perform(json(post("/api/admin/usuarios/" + lucia.getId() + "/password"), datos.admin(),
                "{\"password\": \"clave-nueva-de-lucia\"}")).andExpect(status().isNoContent());
        // Recuperación con token.
        lucia.setResetTokenHash(com.greenfire.seguridad.RecuperacionServiceAcceso.sha256("token-secreto-1"));
        lucia.setResetTokenVence(OffsetDateTime.now().plusHours(1));
        mvc.perform(post("/api/auth/restablecer").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"token-secreto-1\", \"password\": \"clave-recuperada-1\"}"))
                .andExpect(status().isNoContent());
        // Edición del certificado de ARCA.
        var e = centro.getEntidadFiscal();
        mvc.perform(json(put("/api/admin/entidades-fiscales/" + e.getId()), datos.admin(), """
                {"razonSocial": "%s", "cuit": "%s", "condicionIva": "monotributo",
                 "arcaCertificadoRef": "vault://certs/centro", "arcaCertificadoVence": "2027-05-01"}
                """.formatted(e.getRazonSocial(), e.getCuit()))).andExpect(status().isOk());

        String todo = String.join("\n", jdbc.queryForList(
                "select coalesce(detalle, '') || ' ' || coalesce(email_intentado, '') from auditorias", String.class));
        assertThat(todo).doesNotContain("clave-de-pedro-1", "clave-nueva-de-lucia", "clave-recuperada-1",
                "token-secreto-1", "$2a$", "vault://", "2027-05-01");
        assertThat(jdbc.queryForObject(
                "select detalle from auditorias where entidad = 'entidad_fiscal' and accion = 'editar'", String.class))
                .isEqualTo("{\"arcaCertificadoRef\":{\"antes\":null,\"despues\":\"[oculto]\"},"
                        + "\"arcaCertificadoVence\":{\"antes\":null,\"despues\":\"[oculto]\"}}");
    }

    @Test
    void laBaseNoPermiteModificarUnRegistro() {
        long id = registroDePrueba();

        assertThatThrownBy(() -> jdbc.update("update auditorias set accion = 'otra' where id = ?", id))
                .rootCause().hasMessageContaining("no se puede modificar ni borrar");
    }

    @Test
    void laBaseNoPermiteBorrarUnRegistro() {
        long id = registroDePrueba();

        assertThatThrownBy(() -> jdbc.update("delete from auditorias where id = ?", id))
                .rootCause().hasMessageContaining("no se puede modificar ni borrar");
    }

    @Test
    void laBaseNoPermiteVaciarLaTabla() {
        registroDePrueba();

        assertThatThrownBy(() -> jdbc.execute("truncate auditorias"))
                .rootCause().hasMessageContaining("no se puede modificar ni borrar");
    }

    @Test
    void laApiNoTieneFormaDeEditarNiBorrar() throws Exception {
        long id = registroDePrueba();
        RequestPostProcessor admin = datos.admin();

        // La única ruta es GET /api/auditoria: no hay ruta por id ni otros métodos.
        mvc.perform(json(put("/api/auditoria/" + id), admin, "{}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/auditoria/" + id).with(admin)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/auditoria"), admin, "{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(json(post("/api/auditoria"), admin, "{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/auditoria").with(admin)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void elRepositorioSoloPermiteAgregar() {
        assertThat(Arrays.stream(AuditoriaRepository.class.getMethods()).map(m -> m.getName()))
                .containsOnly("save");
    }

    private long registroDePrueba() {
        return jdbc.queryForObject("insert into auditorias (accion) values ('prueba') returning id", Long.class);
    }
}
