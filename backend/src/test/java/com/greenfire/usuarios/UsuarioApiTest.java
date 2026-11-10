package com.greenfire.usuarios;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.seguridad.Perfil;

@PruebaIntegracion
class UsuarioApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;
    @Autowired
    UsuarioRepository usuarios;
    @Autowired
    PasswordEncoder encoder;

    // --- Administrador técnico ---

    @Test
    void elAdminCreaDuenosDeFabricaYDeLocal() throws Exception {
        Ambito fabrica = datos.fabrica();
        Ambito centro = datos.local("Centro");

        mvc.perform(json(post("/api/admin/usuarios"), datos.admin(),
                        dueno("Fabián", "fabian@greenfire.test", fabrica, "dueno_fabrica")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.perfiles[0].perfil").value("dueno_fabrica"));
        mvc.perform(json(post("/api/admin/usuarios"), datos.admin(),
                        dueno("Lucía", "lucia@greenfire.test", centro, "dueno_local")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.perfiles[0].ambitoNombre").value("Centro"));
    }

    @Test
    void elPerfilTieneQueCorresponderAlTipoDeAmbitoYElAdminNoCreaEmpleados() throws Exception {
        Ambito fabrica = datos.fabrica();
        Ambito centro = datos.local("Centro");

        mvc.perform(json(post("/api/admin/usuarios"), datos.admin(),
                        dueno("X", "x1@greenfire.test", centro, "dueno_fabrica")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/admin/usuarios"), datos.admin(),
                        dueno("X", "x2@greenfire.test", fabrica, "dueno_local")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/admin/usuarios"), datos.admin(),
                        dueno("X", "x3@greenfire.test", centro, "empleado_local")))
                .andExpect(status().isConflict());
    }

    @Test
    void elAdminSumaElLocalCentralAUnDuenoDeFabricaQueDespuesEntraConUnaSolaCuenta() throws Exception {
        Ambito fabrica = datos.fabrica();
        Ambito central = datos.local("Central");
        Usuario duena = datos.usuario("Duena");
        datos.perfil(duena, fabrica, Perfil.dueno_fabrica);

        mvc.perform(json(post("/api/admin/usuarios/" + duena.getId() + "/perfiles"), datos.admin(),
                        "{\"ambitoId\": %d, \"perfil\": \"dueno_local\"}".formatted(central.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perfiles[*].perfil").value(containsInAnyOrder("dueno_fabrica", "dueno_local")));
        mvc.perform(json(post("/api/admin/usuarios/" + duena.getId() + "/perfiles"), datos.admin(),
                        "{\"ambitoId\": %d, \"perfil\": \"dueno_local\"}".formatted(central.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void elAdminRestableceUnaContrasenaYDesbloqueaLaCuenta() throws Exception {
        Usuario ana = datos.usuario("Ana");
        datos.perfil(ana, datos.local("Centro"), Perfil.dueno_local);
        ana.setIntentosFallidos(3);

        mvc.perform(json(post("/api/admin/usuarios/" + ana.getId() + "/password"), datos.admin(),
                        "{\"password\": \"clave-nueva-del-admin\"}"))
                .andExpect(status().isNoContent());

        assertThat(encoder.matches("clave-nueva-del-admin", ana.getPasswordHash())).isTrue();
        assertThat(ana.getIntentosFallidos()).isZero();
        Datos.login(mvc, ana.getEmail(), "clave-nueva-del-admin");
    }

    // --- Dueño de local ---

    @Test
    void elDuenoDeLocalCreaEmpleadosDeSuLocalConContrasenaEnBCrypt() throws Exception {
        Ambito centro = datos.local("Centro");

        mvc.perform(json(post("/api/usuarios"), datos.en(centro, Perfil.dueno_local),
                        empleado("Pedro", "pedro@greenfire.test", "clave-de-pedro-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.perfil").value("empleado_local"));

        Usuario pedro = usuarios.buscarPorEmail("pedro@greenfire.test").orElseThrow();
        assertThat(pedro.getPasswordHash()).startsWith("$2a$10$");
        assertThat(encoder.matches("clave-de-pedro-1", pedro.getPasswordHash())).isTrue();
        EventoAuditoria alta = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals("crear")).findFirst().orElseThrow();
        assertThat(alta.detalle().get("ambitoId")).isEqualTo(centro.getId());
        assertThat(alta.detalle()).doesNotContainKey("password");
        assertThat(alta.detalle().toString()).doesNotContain("clave-de-pedro-1");
    }

    @Test
    void laContrasenaTieneQueTenerAlMenosDiezCaracteres() throws Exception {
        mvc.perform(json(post("/api/usuarios"), datos.en(datos.local("Centro"), Perfil.dueno_local),
                        empleado("Pedro", "pedro@greenfire.test", "123456789")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos", hasKey("password")));
    }

    @Test
    void elEmailNoSePuedeRepetirAunqueCambienLasMayusculas() throws Exception {
        Usuario ana = datos.usuario("Ana");

        mvc.perform(json(post("/api/usuarios"), datos.en(datos.local("Centro"), Perfil.dueno_local),
                        empleado("Otra", ana.getEmail().toUpperCase(), "clave-de-prueba-2")))
                .andExpect(status().isConflict());
    }

    @Test
    void elUsuarioSoloAdmiteMinusculasNumerosPuntoYGuionBajoDe3A30Caracteres() throws Exception {
        RequestPostProcessor dueno = datos.en(datos.local("Centro"), Perfil.dueno_local);

        for (String invalido : List.of("Pedro", "pedro@greenfire.test", "pedro perez", "pedro-perez", "pedrí", "pe",
                "p".repeat(31), "")) {
            mvc.perform(json(post("/api/usuarios"), dueno,
                            empleado("Pedro", invalido, "pedro@greenfire.test", "clave-de-pedro-1")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.campos", hasKey("usuario")));
        }
        mvc.perform(json(post("/api/usuarios"), dueno,
                        empleado("Pedro", "pedro.perez_2", "pedro@greenfire.test", "clave-de-pedro-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuario").value("pedro.perez_2"));
    }

    @Test
    void elUsuarioNoSePuedeRepetir() throws Exception {
        Usuario ana = datos.usuario("Ana");

        mvc.perform(json(post("/api/usuarios"), datos.en(datos.local("Centro"), Perfil.dueno_local),
                        empleado("Otra", ana.getUsuario(), "otra@greenfire.test", "clave-de-prueba-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Ya existe un usuario con ese nombre de usuario."));
        mvc.perform(json(post("/api/admin/usuarios"), datos.admin(), """
                        {"nombre": "Otra", "usuario": "%s", "email": "otra@greenfire.test",
                         "password": "clave-inicial-1", "ambitoId": %d, "perfil": "dueno_local"}
                        """.formatted(ana.getUsuario(), datos.local("Norte").getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void elAltaQuedaEnAuditoriaConElUsuario() throws Exception {
        mvc.perform(json(post("/api/usuarios"), datos.en(datos.local("Centro"), Perfil.dueno_local),
                        empleado("Pedro", "pedro", "pedro@greenfire.test", "clave-de-pedro-1")))
                .andExpect(status().isCreated());

        assertThat(eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("crear")))
                .singleElement()
                .satisfies(e -> assertThat(e.detalle().get("usuario")).isEqualTo("pedro"));
    }

    @Test
    void elDuenoDeLocalVeSoloLosUsuariosDeSuLocal() throws Exception {
        Ambito centro = datos.local("Centro");
        Ambito norte = datos.local("Norte");
        datos.perfil(datos.usuario("Pedro"), centro, Perfil.empleado_local);
        datos.perfil(datos.usuario("Nora"), norte, Perfil.empleado_local);

        mvc.perform(get("/api/usuarios").with(datos.en(centro, Perfil.dueno_local)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].nombre").value(containsInAnyOrder("Pedro", "dueno_local")));
    }

    @Test
    void elDuenoDeLocalSoloEditaYDesactivaEmpleadosNoOtrosDuenos() throws Exception {
        Ambito centro = datos.local("Centro");
        Usuario socio = datos.usuario("Socio");
        datos.perfil(socio, centro, Perfil.dueno_local);
        RequestPostProcessor dueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(post("/api/usuarios/" + socio.getId() + "/desactivar").with(dueno))
                .andExpect(status().isForbidden());
    }

    @Test
    void elDuenoDeFabricaNoCreaUsuarios() throws Exception {
        RequestPostProcessor duenoFabrica = datos.en(datos.fabrica(), Perfil.dueno_fabrica);

        mvc.perform(json(post("/api/usuarios"), duenoFabrica,
                        empleado("Pedro", "pedro@greenfire.test", "clave-de-pedro-1")))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/admin/usuarios"), duenoFabrica,
                        dueno("Pedro", "pedro@greenfire.test", datos.local("Centro"), "dueno_local")))
                .andExpect(status().isForbidden());
    }

    static String dueno(String nombre, String email, Ambito ambito, String perfil) {
        return """
                {"nombre": "%s", "usuario": "%s", "email": "%s", "password": "clave-inicial-1", "ambitoId": %d,
                 "perfil": "%s"}
                """.formatted(nombre, usuarioDe(nombre), email, ambito.getId(), perfil);
    }

    static String empleado(String nombre, String email, String password) {
        return empleado(nombre, usuarioDe(nombre), email, password);
    }

    static String empleado(String nombre, String usuario, String email, String password) {
        return """
                {"nombre": "%s", "usuario": "%s", "email": "%s", "password": "%s"}
                """.formatted(nombre, usuario, email, password);
    }

    /** Un usuario que no choca con los de {@link Datos#usuario(String)}, que no llevan punto. */
    private static String usuarioDe(String nombre) {
        return "nuevo." + nombre.toLowerCase().replaceAll("[^a-z]", "");
    }
}
