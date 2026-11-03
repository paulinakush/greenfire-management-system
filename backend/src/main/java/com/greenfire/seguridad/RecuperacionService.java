package com.greenfire.seguridad;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.auditoria.Auditor;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.usuarios.Usuario;
import com.greenfire.usuarios.UsuarioRepository;

/**
 * Recuperación de contraseña. En desarrollo el link va al log; en producción no se genera link y la
 * contraseña la restablece el administrador técnico.
 */
@Service
@Transactional
public class RecuperacionService {

    private static final Logger log = LoggerFactory.getLogger(RecuperacionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UsuarioRepository usuarios;
    private final PasswordEncoder encoder;
    private final SesionHttp sesionHttp;
    private final Auditor auditor;
    private final Duration vigencia;
    private final boolean logEnlace;

    public RecuperacionService(UsuarioRepository usuarios, PasswordEncoder encoder, SesionHttp sesionHttp,
            Auditor auditor, @Value("${greenfire.recuperacion.vigencia}") Duration vigencia,
            @Value("${greenfire.recuperacion.log-enlace}") boolean logEnlace) {
        this.usuarios = usuarios;
        this.encoder = encoder;
        this.sesionHttp = sesionHttp;
        this.auditor = auditor;
        this.vigencia = vigencia;
        this.logEnlace = logEnlace;
    }

    /** Siempre responde igual, exista o no el email. */
    public void solicitar(String email) {
        Optional<Usuario> usuario = usuarios.buscarPorEmail(email.trim()).filter(Usuario::isActivo);
        auditor.registrarAcceso("recuperacion_solicitada", usuario.map(Usuario::getId).orElse(null), email, Map.of());
        if (usuario.isEmpty() || !logEnlace) {
            return;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        usuario.get().setResetTokenHash(sha256(token));
        usuario.get().setResetTokenVence(OffsetDateTime.now().plus(vigencia));
        log.info("[solo desarrollo] Link para restablecer la contraseña de {}: /privado/restablecer.html?token={}",
                email, token);
    }

    public void restablecer(String token, String password) {
        Usuario usuario = usuarios.findByResetTokenHash(sha256(token))
                .filter(u -> u.getResetTokenVence() != null && u.getResetTokenVence().isAfter(OffsetDateTime.now()))
                .orElseThrow(() -> new ReglaNegocioException("El link para restablecer la contraseña no es válido o venció."));
        usuario.setPasswordHash(encoder.encode(password));
        usuario.setResetTokenHash(null);
        usuario.setResetTokenVence(null);
        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);
        sesionHttp.cerrarTodas(usuario.getId());
        auditor.registrarAcceso("password_restablecida", usuario.getId(), null, Map.of());
    }

    static String sha256(String valor) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
