package com.greenfire.seguridad;

import static com.greenfire.Datos.con;
import static com.greenfire.Datos.json;
import static com.greenfire.Datos.login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.usuarios.Usuario;

import jakarta.servlet.http.Cookie;

/** Los casos de acceso pedidos para la etapa 1. */
@PruebaIntegracion
class AccesoApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;

    @Test
    void unEmpleadoNoVeOtroLocal() throws Exception {
        Ambito centro = datos.local("Centro");
        Ambito norte = datos.local("Norte");
        Usuario pedro = datos.usuario("Pedro");
        datos.perfil(pedro, centro, Perfil.empleado_local);
        Cookie sesion = login(mvc, pedro.getEmail(), Datos.PASSWORD);

        mvc.perform(get("/api/ambito").with(con(sesion)))
                .andExpect(jsonPath("$.nombre").value("Centro"));
        mvc.perform(post("/api/auth/ambito").with(con(sesion)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ambitoId\": " + norte.getId() + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ambito").with(con(sesion)))
                .andExpect(jsonPath("$.nombre").value("Centro"));
        mvc.perform(get("/api/usuarios").with(con(sesion)))
                .andExpect(status().isForbidden());
    }

    @Test
    void unDuenoDeLocalNoTocaUsuariosDeOtroLocal() throws Exception {
        Ambito centro = datos.local("Centro");
        Ambito norte = datos.local("Norte");
        Usuario nora = datos.usuario("Nora");
        datos.perfil(nora, norte, Perfil.empleado_local);
        RequestPostProcessor duenoCentro = datos.en(centro, Perfil.dueno_local);

        mvc.perform(json(put("/api/usuarios/" + nora.getId()), duenoCentro,
                        "{\"nombre\": \"Cambiado\", \"email\": \"cambiado@greenfire.test\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/usuarios/" + nora.getId() + "/desactivar").with(duenoCentro))
                .andExpect(status().isNotFound());
    }

    @Test
    void unDuenoDeLocalNoCreaDuenos() throws Exception {
        Ambito centro = datos.local("Centro");
        Usuario pedro = datos.usuario("Pedro");
        datos.perfil(pedro, centro, Perfil.empleado_local);
        RequestPostProcessor dueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(json(post("/api/admin/usuarios"), dueno, """
                        {"nombre": "Otro", "usuario": "otro", "email": "otro@greenfire.test", "password": "clave-inicial-1",
                         "ambitoId": %d, "perfil": "dueno_local"}
                        """.formatted(centro.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/usuarios"), dueno, """
                        {"nombre": "Otro", "usuario": "otro", "email": "otro@greenfire.test", "password": "clave-inicial-1",
                         "perfil": "dueno_local"}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/admin/usuarios/" + pedro.getId() + "/perfiles"), dueno,
                        "{\"ambitoId\": %d, \"perfil\": \"dueno_local\"}".formatted(centro.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void elAdministradorTecnicoNoVeDatosComerciales() throws Exception {
        datos.local("Centro");
        RequestPostProcessor admin = datos.admin();

        mvc.perform(get("/api/ambito").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/ambito/entidad-fiscal").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/usuarios").with(admin)).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/ambito/contacto"), admin, "{\"direccion\": \"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unEmpleadoDesactivadoPorSuDuenoPierdeElAccesoAlInstante() throws Exception {
        Ambito centro = datos.local("Centro");
        Usuario pedro = datos.usuario("Pedro");
        datos.perfil(pedro, centro, Perfil.empleado_local);
        Cookie sesionPedro = login(mvc, pedro.getEmail(), Datos.PASSWORD);
        mvc.perform(get("/api/ambito").with(con(sesionPedro))).andExpect(status().isOk());

        mvc.perform(post("/api/usuarios/" + pedro.getId() + "/desactivar").with(datos.en(centro, Perfil.dueno_local)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ambito").with(con(sesionPedro))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").with(con(sesionPedro))).andExpect(status().isUnauthorized());
    }

    @Test
    void unUsuarioDesactivadoPorElAdminPierdeElAccesoAlInstante() throws Exception {
        Ambito centro = datos.local("Centro");
        Usuario lucia = datos.usuario("Lucia");
        datos.perfil(lucia, centro, Perfil.dueno_local);
        Cookie sesionLucia = login(mvc, lucia.getEmail(), Datos.PASSWORD);

        mvc.perform(post("/api/admin/usuarios/" + lucia.getId() + "/desactivar").with(datos.admin()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ambito").with(con(sesionLucia))).andExpect(status().isUnauthorized());
    }

    @Test
    void siSeDesactivaElAmbitoSusUsuariosPierdenElAcceso() throws Exception {
        Ambito centro = datos.local("Centro");
        Usuario lucia = datos.usuario("Lucia");
        datos.perfil(lucia, centro, Perfil.dueno_local);
        Cookie sesionLucia = login(mvc, lucia.getEmail(), Datos.PASSWORD);

        mvc.perform(post("/api/admin/ambitos/" + centro.getId() + "/desactivar").with(datos.admin()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/ambito").with(con(sesionLucia))).andExpect(status().isUnauthorized());
    }
}
