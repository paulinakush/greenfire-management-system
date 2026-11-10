package com.greenfire.seguridad;

import static com.greenfire.Datos.con;
import static com.greenfire.Datos.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.usuarios.Usuario;

import jakarta.servlet.http.Cookie;

@PruebaIntegracion
class AuthApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;

    @Test
    void conUnSoloPerfilEntraDirectoASuAmbitoConCookieHttpOnlyYSameSiteLax() throws Exception {
        Ambito centro = datos.local("Centro");
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, centro, Perfil.dueno_local);

        intentarLogin(ana.getEmail(), Datos.PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usuario.email").value(ana.getEmail()))
                .andExpect(jsonPath("$.ambitoActivo.ambitoNombre").value("Centro"))
                .andExpect(jsonPath("$.ambitoActivo.perfil").value("dueno_local"))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));

        Cookie sesion = login(mvc, ana.getEmail(), Datos.PASSWORD);
        mvc.perform(get("/api/ambito").with(con(sesion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Centro"));
        assertThat(eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("login")))
                .allSatisfy(e -> {
                    assertThat(e.usuarioId()).isEqualTo(ana.getId());
                    assertThat(e.ambitoId()).isEqualTo(centro.getId());
                    assertThat(e.perfil()).isEqualTo(Perfil.dueno_local);
                });
    }

    @Test
    void elMensajeEsElMismoSiElEmailNoExisteOSiLaContrasenaEsIncorrecta() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);

        String emailInexistente = intentarLogin("nadie@greenfire.test", Datos.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String passwordIncorrecta = intentarLogin(ana.getEmail(), "otra-clave-incorrecta")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(emailInexistente).isEqualTo(passwordIncorrecta);
        assertThat(emailInexistente).doesNotContain("nadie").doesNotContain(ana.getEmail());
    }

    @Test
    void losIntentosFallidosQuedanEnAuditoriaConEmailEIpAunqueElEmailNoExista() throws Exception {
        Usuario ana = datos.usuario("Ana");

        intentarLogin("nadie@greenfire.test", "cualquier-clave");
        intentarLogin(ana.getEmail(), "otra-clave-incorrecta");

        List<EventoAuditoria> fallidos = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals("login_fallido")).toList();
        assertThat(fallidos).hasSize(2);
        assertThat(fallidos.get(0).usuarioId()).isNull();
        assertThat(fallidos.get(0).emailIntentado()).isEqualTo("nadie@greenfire.test");
        assertThat(fallidos.get(0).ip()).isEqualTo("127.0.0.1");
        assertThat(fallidos.get(0).detalle().get("motivo")).isEqualTo("usuario_inexistente");
        assertThat(fallidos.get(1).usuarioId()).isEqualTo(ana.getId());
        assertThat(fallidos.get(1).emailIntentado()).isEqualTo(ana.getEmail());
        assertThat(fallidos.get(1).detalle().get("motivo")).isEqualTo("password_incorrecta");
    }

    @Test
    void despuesDeCincoIntentosFallidosLaCuentaSeBloqueaQuinceMinutos() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);

        for (int i = 0; i < 5; i++) {
            intentarLogin(ana.getEmail(), "otra-clave-incorrecta").andExpect(status().isUnauthorized());
        }
        assertThat(ana.getBloqueadoHasta()).isBetween(OffsetDateTime.now().plusMinutes(14),
                OffsetDateTime.now().plusMinutes(16));
        assertThat(eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("usuario_bloqueado")))
                .hasSize(1);

        // Bloqueada, ni con la contraseña correcta entra.
        intentarLogin(ana.getEmail(), Datos.PASSWORD).andExpect(status().isUnauthorized());

        // Pasado el bloqueo, entra.
        ana.setBloqueadoHasta(OffsetDateTime.now().minusSeconds(1));
        intentarLogin(ana.getEmail(), Datos.PASSWORD).andExpect(status().isOk());
        assertThat(ana.getBloqueadoHasta()).isNull();
    }

    @Test
    void elLoginSinTokenCsrfSeRechaza() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credenciales(ana.getEmail(), Datos.PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    void conPerfilesEnDosAmbitosEligeYCambiaDeAmbitoSinCerrarSesion() throws Exception {
        Ambito fabrica = datos.fabrica();
        Ambito central = datos.local("Central");
        Usuario duena = datos.usuario("Duena");
        datos.perfil(duena, fabrica, Perfil.dueno_fabrica);
        datos.perfil(duena, central, Perfil.dueno_local);

        intentarLogin(duena.getEmail(), Datos.PASSWORD)
                .andExpect(jsonPath("$.perfiles", hasSize(2)))
                .andExpect(jsonPath("$.ambitoActivo").isEmpty());
        Cookie sesion = login(mvc, duena.getEmail(), Datos.PASSWORD);

        // Sin ámbito elegido no accede a datos de ningún ámbito.
        mvc.perform(get("/api/ambito").with(con(sesion))).andExpect(status().isForbidden());

        elegir(sesion, fabrica).andExpect(jsonPath("$.ambitoActivo.perfil").value("dueno_fabrica"));
        mvc.perform(get("/api/ambito").with(con(sesion))).andExpect(jsonPath("$.nombre").value("Fábrica"));

        elegir(sesion, central).andExpect(jsonPath("$.ambitoActivo.perfil").value("dueno_local"));
        mvc.perform(get("/api/ambito").with(con(sesion))).andExpect(jsonPath("$.nombre").value("Central"));
        mvc.perform(get("/api/auth/me").with(con(sesion)))
                .andExpect(jsonPath("$.ambitoActivo.ambitoNombre").value("Central"));

        assertThat(eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("cambio_ambito")))
                .hasSize(2)
                .last()
                .satisfies(e -> {
                    assertThat(e.ambitoId()).isEqualTo(central.getId());
                    assertThat(e.perfil()).isEqualTo(Perfil.dueno_local);
                });
    }

    @Test
    void elLogoutCierraLaSesion() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);
        Cookie sesion = login(mvc, ana.getEmail(), Datos.PASSWORD);

        mvc.perform(post("/api/auth/logout").with(con(sesion))).andExpect(status().isNoContent());

        mvc.perform(get("/api/auth/me").with(con(sesion))).andExpect(status().isUnauthorized());
    }

    @Test
    void laRecuperacionRespondeIgualAunqueElEmailNoExista() throws Exception {
        mvc.perform(post("/api/auth/recuperar").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"nadie@greenfire.test\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void elTokenDeRecuperacionSirveUnaSolaVezYCierraLasSesionesAbiertas() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);
        Cookie sesionAnterior = login(mvc, ana.getEmail(), Datos.PASSWORD);
        ana.setResetTokenHash(RecuperacionService.sha256("token-de-prueba"));
        ana.setResetTokenVence(OffsetDateTime.now().plusHours(1));

        restablecer("token-de-prueba", "corta").andExpect(status().isBadRequest());
        restablecer("token-de-prueba", "nueva-clave-segura").andExpect(status().isNoContent());
        restablecer("token-de-prueba", "otra-clave-segura").andExpect(status().isConflict());

        mvc.perform(get("/api/auth/me").with(con(sesionAnterior))).andExpect(status().isUnauthorized());
        intentarLogin(ana.getEmail(), "nueva-clave-segura").andExpect(status().isOk());
    }

    @Test
    void unTokenDeRecuperacionVencidoNoSirve() throws Exception {
        Usuario ana = datos.usuario("Ana");
        ana.setResetTokenHash(RecuperacionService.sha256("token-vencido"));
        ana.setResetTokenVence(OffsetDateTime.now().minusMinutes(1));

        restablecer("token-vencido", "nueva-clave-segura").andExpect(status().isConflict());
    }

    // --- Login con usuario o email ---

    @Test
    void entraConElUsuarioOConElEmailSinDistinguirMayusculas() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);

        for (String ingresado : List.of(ana.getUsuario(), ana.getEmail(), ana.getUsuario().toUpperCase(),
                ana.getEmail().toUpperCase(), "  " + ana.getUsuario() + " ")) {
            intentarLogin(ingresado, Datos.PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.usuario.id").value(ana.getId().intValue()));
        }
    }

    @Test
    void laSesionIncluyeElUsuario() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);

        intentarLogin(ana.getUsuario(), Datos.PASSWORD)
                .andExpect(jsonPath("$.usuario.usuario").value(ana.getUsuario()))
                .andExpect(jsonPath("$.usuario.email").value(ana.getEmail()));
        Cookie sesion = login(mvc, ana.getUsuario(), Datos.PASSWORD);
        mvc.perform(get("/api/auth/me").with(con(sesion)))
                .andExpect(jsonPath("$.usuario.usuario").value(ana.getUsuario()));
    }

    @Test
    void todaviaAceptaElCampoEmailEnElPedido() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);

        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}"
                                .formatted(ana.getUsuario(), Datos.PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void elMensajeEsElMismoParaCualquierFallo() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);
        Usuario bloqueada = datos.usuario("Bloqueada");
        datos.perfil(bloqueada, datos.local("Norte"), Perfil.dueno_local);
        bloqueada.setBloqueadoHasta(OffsetDateTime.now().plusMinutes(10));
        Usuario inactiva = datos.usuario("Inactiva");
        datos.perfil(inactiva, datos.local("Sur"), Perfil.dueno_local);
        inactiva.setActivo(false);

        List<String> respuestas = new ArrayList<>();
        for (String[] intento : new String[][] {
                {"nadie", Datos.PASSWORD},
                {"nadie@greenfire.test", Datos.PASSWORD},
                {ana.getUsuario(), "otra-clave-incorrecta"},
                {ana.getEmail(), "otra-clave-incorrecta"},
                {bloqueada.getUsuario(), Datos.PASSWORD},
                {inactiva.getUsuario(), Datos.PASSWORD}}) {
            respuestas.add(intentarLogin(intento[0], intento[1])
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.mensaje").value(LoginService.LOGIN_FALLIDO))
                    .andReturn().getResponse().getContentAsString());
        }
        assertThat(respuestas).containsOnly(respuestas.get(0));
    }

    @Test
    void losIntentosFallidosGuardanLoQueSeEscribioEnElCampo() throws Exception {
        Usuario ana = datos.usuario("Ana");

        intentarLogin("Nadie.Que.Exista", "cualquier-clave");
        intentarLogin(ana.getUsuario().toUpperCase(), "otra-clave-incorrecta");

        List<EventoAuditoria> fallidos = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals("login_fallido")).toList();
        assertThat(fallidos).extracting(EventoAuditoria::emailIntentado)
                .containsExactly("Nadie.Que.Exista", ana.getUsuario().toUpperCase());
        assertThat(fallidos).extracting(EventoAuditoria::usuarioId).containsExactly(null, ana.getId());
    }

    @Test
    void elBloqueoCuentaLosIntentosConUsuarioYConEmailJuntos() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);

        for (int i = 0; i < 5; i++) {
            intentarLogin(i % 2 == 0 ? ana.getUsuario() : ana.getEmail(), "otra-clave-incorrecta")
                    .andExpect(status().isUnauthorized());
        }
        assertThat(ana.getBloqueadoHasta()).isAfter(OffsetDateTime.now().plusMinutes(14));

        intentarLogin(ana.getUsuario(), Datos.PASSWORD).andExpect(status().isUnauthorized());
        intentarLogin(ana.getEmail(), Datos.PASSWORD).andExpect(status().isUnauthorized());
    }

    private ResultActions intentarLogin(String usuario, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(credenciales(usuario, password)));
    }

    private ResultActions elegir(Cookie sesion, Ambito ambito) throws Exception {
        return mvc.perform(post("/api/auth/ambito").with(con(sesion)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ambitoId\": " + ambito.getId() + "}"));
    }

    private ResultActions restablecer(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/restablecer").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\", \"password\": \"%s\"}".formatted(token, password)));
    }

    private static String credenciales(String usuario, String password) {
        return "{\"usuario\": \"%s\", \"password\": \"%s\"}".formatted(usuario, password);
    }
}
