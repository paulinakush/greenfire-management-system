package com.greenfire.config;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.greenfire.auditoria.Auditor;
import com.greenfire.usuarios.Usuario;
import com.greenfire.usuarios.UsuarioRepository;

/**
 * Al arrancar, crea el administrador técnico a partir de ADMIN_NOMBRE, ADMIN_USUARIO, ADMIN_EMAIL y
 * ADMIN_PASSWORD, solo si todavía no existe ninguno.
 */
@Component
public class AdminInicialRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInicialRunner.class);

    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final Auditor auditor;
    private final String nombre;
    private final String usuario;
    private final String email;
    private final String password;

    public AdminInicialRunner(UsuarioRepository usuarios, PasswordEncoder encoder, Auditor auditor,
            @Value("${greenfire.admin-inicial.nombre}") String nombre,
            @Value("${greenfire.admin-inicial.usuario}") String usuario,
            @Value("${greenfire.admin-inicial.email}") String email,
            @Value("${greenfire.admin-inicial.password}") String password) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.auditor = auditor;
        this.nombre = nombre;
        this.usuario = usuario;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (usuarios.existsByEsAdminTecnicoTrue()) {
            return;
        }
        if (!StringUtils.hasText(usuario) || !StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            log.warn("No hay administrador técnico y faltan ADMIN_USUARIO, ADMIN_EMAIL o ADMIN_PASSWORD: "
                    + "no se crea ninguno.");
            return;
        }
        if (!usuario.trim().matches("[a-z0-9._]{3,30}")) {
            throw new IllegalStateException("ADMIN_USUARIO debe tener entre 3 y 30 caracteres: letras minúsculas, "
                    + "números, punto o guion bajo.");
        }
        if (password.length() < 10) {
            throw new IllegalStateException("ADMIN_PASSWORD debe tener al menos 10 caracteres.");
        }
        Usuario admin = new Usuario();
        admin.setNombre(StringUtils.hasText(nombre) ? nombre.trim() : "Administrador técnico");
        admin.setUsuario(usuario.trim());
        admin.setEmail(email.trim());
        admin.setPasswordHash(encoder.encode(password));
        admin.setEsAdminTecnico(true);
        usuarios.save(admin);
        auditor.registrarAcceso("crear_admin_inicial", admin.getId(), null, Map.of("usuario", admin.getUsuario(), "email", admin.getEmail()));
        log.info("Administrador técnico inicial creado: {}", admin.getUsuario());
    }
}
