package com.greenfire.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.greenfire.PruebaIntegracion;
import com.greenfire.auditoria.Auditor;
import com.greenfire.usuarios.Usuario;
import com.greenfire.usuarios.UsuarioRepository;

@PruebaIntegracion
class AdminInicialRunnerTest {

    @Autowired
    UsuarioRepository usuarios;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    Auditor auditor;

    @Test
    void creaElAdministradorTecnicoSoloSiNoExisteNinguno() {
        runner("admin@greenfire.test", "clave-del-admin-1").run(null);
        runner("otro@greenfire.test", "clave-del-admin-2").run(null);

        assertThat(usuarios.findAll()).filteredOn(Usuario::isEsAdminTecnico)
                .singleElement()
                .satisfies(admin -> {
                    assertThat(admin.getUsuario()).isEqualTo("administrador");
                    assertThat(admin.getEmail()).isEqualTo("admin@greenfire.test");
                    assertThat(encoder.matches("clave-del-admin-1", admin.getPasswordHash())).isTrue();
                });
    }

    @Test
    void sinVariablesNoCreaNada() {
        runner("", "").run(null);

        assertThat(usuarios.existsByEsAdminTecnicoTrue()).isFalse();
    }

    @Test
    void rechazaUnaContrasenaCorta() {
        assertThatThrownBy(() -> runner("admin@greenfire.test", "corta").run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rechazaUnUsuarioInvalido() {
        assertThatThrownBy(() -> runner("Admin Tecnico", "admin@greenfire.test", "clave-del-admin-1").run(null))
                .isInstanceOf(IllegalStateException.class);
    }

    private AdminInicialRunner runner(String email, String password) {
        return runner("administrador", email, password);
    }

    private AdminInicialRunner runner(String usuario, String email, String password) {
        return new AdminInicialRunner(usuarios, encoder, auditor, "Admin", usuario, email, password);
    }
}
