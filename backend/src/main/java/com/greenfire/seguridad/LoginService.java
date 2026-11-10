package com.greenfire.seguridad;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.auditoria.Auditor;
import com.greenfire.comun.AccesoDenegadoException;
import com.greenfire.comun.NoAutenticadoException;
import com.greenfire.seguridad.dto.SesionResponse;
import com.greenfire.seguridad.dto.SesionResponse.PerfilSesion;
import com.greenfire.seguridad.dto.SesionResponse.UsuarioSesion;
import com.greenfire.usuarios.Usuario;
import com.greenfire.usuarios.UsuarioAmbito;
import com.greenfire.usuarios.UsuarioAmbitoRepository;
import com.greenfire.usuarios.UsuarioRepository;

import jakarta.servlet.http.HttpServletRequest;

@Service
@Transactional
public class LoginService {

    /**
     * Único mensaje para cualquier login fallido (usuario o email inexistente, contraseña incorrecta, cuenta
     * bloqueada o inactiva), para no revelar qué usuarios existen.
     */
    public static final String LOGIN_FALLIDO = "No se pudo iniciar sesión. Revisá el usuario o email y la "
            + "contraseña. Después de varios intentos fallidos, la cuenta se bloquea temporalmente.";

    private final UsuarioRepository usuarios;
    private final UsuarioAmbitoRepository perfiles;
    private final PasswordEncoder encoder;
    private final SesionHttp sesionHttp;
    private final Acceso acceso;
    private final Auditor auditor;
    private final int intentosMaximos;
    private final Duration bloqueo;
    /** Se compara contra este hash cuando el usuario no existe, para que la respuesta tarde lo mismo. */
    private final String hashFicticio;

    public LoginService(UsuarioRepository usuarios, UsuarioAmbitoRepository perfiles, PasswordEncoder encoder,
            SesionHttp sesionHttp, Acceso acceso, Auditor auditor,
            @Value("${greenfire.seguridad.intentos-maximos}") int intentosMaximos,
            @Value("${greenfire.seguridad.bloqueo}") Duration bloqueo) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.encoder = encoder;
        this.sesionHttp = sesionHttp;
        this.acceso = acceso;
        this.auditor = auditor;
        this.intentosMaximos = intentosMaximos;
        this.bloqueo = bloqueo;
        this.hashFicticio = encoder.encode("hash-ficticio-para-igualar-tiempos");
    }

    /**
     * Devuelve vacío si el login falla. No lanza excepción, para que se guarden los intentos fallidos,
     * el bloqueo y la auditoría. {@code ingresado} es el usuario o el email, tal como se escribió; en los
     * intentos fallidos queda en la auditoría.
     */
    public Optional<SesionResponse> login(String ingresado, String password, HttpServletRequest request) {
        OffsetDateTime ahora = OffsetDateTime.now();
        Optional<Usuario> encontrado = usuarios.buscarPorUsuarioOEmail(ingresado.trim());
        if (encontrado.isEmpty()) {
            encoder.matches(password, hashFicticio);
            return fallido(null, ingresado, "usuario_inexistente");
        }
        Usuario usuario = encontrado.get();
        if (usuario.estaBloqueado(ahora)) {
            return fallido(usuario.getId(), ingresado, "cuenta_bloqueada");
        }
        if (!encoder.matches(password, usuario.getPasswordHash())) {
            registrarIntentoFallido(usuario, ingresado, ahora);
            return fallido(usuario.getId(), ingresado, "password_incorrecta");
        }
        if (!usuario.isActivo()) {
            return fallido(usuario.getId(), ingresado, "usuario_inactivo");
        }
        List<UsuarioAmbito> vigentes = perfilesVigentes(usuario.getId());
        if (!usuario.isEsAdminTecnico() && vigentes.isEmpty()) {
            return fallido(usuario.getId(), ingresado, "sin_perfiles_activos");
        }

        usuario.setIntentosFallidos(0);
        usuario.setBloqueadoHasta(null);
        usuario.setUltimoAcceso(ahora);

        Sesion sesion;
        if (usuario.isEsAdminTecnico()) {
            sesion = Sesion.adminTecnico(usuario.getId());
        } else if (vigentes.size() == 1) {
            sesion = Sesion.enAmbito(usuario.getId(), vigentes.get(0).getAmbito().getId(), vigentes.get(0).getPerfil());
        } else {
            sesion = Sesion.sinAmbito(usuario.getId());
        }
        sesionHttp.iniciar(request, sesion);
        auditor.registrarAcceso("login", usuario.getId(), null, Map.of());
        return Optional.of(respuesta(usuario, vigentes, sesion));
    }

    /** Elige o cambia el ámbito de trabajo sin cerrar la sesión. */
    public SesionResponse elegirAmbito(Long ambitoId, HttpServletRequest request) {
        Sesion actual = acceso.sesion();
        if (actual.adminTecnico()) {
            throw new AccesoDenegadoException();
        }
        UsuarioAmbito perfil = perfiles.deUsuarioEnAmbito(actual.usuarioId(), ambitoId)
                .filter(UsuarioAmbito::vigente)
                .orElseThrow(AccesoDenegadoException::new);

        Sesion nueva = Sesion.enAmbito(actual.usuarioId(), ambitoId, perfil.getPerfil());
        sesionHttp.actualizar(request, nueva);
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("ambitoAnterior", actual.ambitoId());
        detalle.put("ambitoNuevo", ambitoId);
        auditor.registrarAcceso("cambio_ambito", actual.usuarioId(), null, detalle);
        return respuesta(perfil.getUsuario(), perfilesVigentes(actual.usuarioId()), nueva);
    }

    @Transactional(readOnly = true)
    public SesionResponse actual() {
        Sesion sesion = acceso.sesion();
        Usuario usuario = usuarios.findById(sesion.usuarioId()).orElseThrow(NoAutenticadoException::new);
        return respuesta(usuario, perfilesVigentes(usuario.getId()), sesion);
    }

    public void logout(HttpServletRequest request) {
        Sesion sesion = acceso.sesion();
        auditor.registrar("logout", "usuario", sesion.usuarioId(), Map.of());
        sesionHttp.cerrar(request);
    }

    private void registrarIntentoFallido(Usuario usuario, String ingresado, OffsetDateTime ahora) {
        usuario.setIntentosFallidos(usuario.getIntentosFallidos() + 1);
        if (usuario.getIntentosFallidos() >= intentosMaximos) {
            usuario.setIntentosFallidos(0);
            usuario.setBloqueadoHasta(ahora.plus(bloqueo));
            auditor.registrarAcceso("usuario_bloqueado", usuario.getId(), ingresado,
                    Map.of("hasta", usuario.getBloqueadoHasta().toString()));
        }
    }

    private Optional<SesionResponse> fallido(Long usuarioId, String ingresado, String motivo) {
        auditor.registrarAcceso("login_fallido", usuarioId, ingresado, Map.of("motivo", motivo));
        return Optional.empty();
    }

    private List<UsuarioAmbito> perfilesVigentes(Long usuarioId) {
        return perfiles.deUsuario(usuarioId).stream().filter(UsuarioAmbito::vigente).toList();
    }

    private static SesionResponse respuesta(Usuario usuario, List<UsuarioAmbito> vigentes, Sesion sesion) {
        List<PerfilSesion> lista = vigentes.stream()
                .map(ua -> new PerfilSesion(ua.getAmbito().getId(), ua.getAmbito().getNombre(),
                        ua.getAmbito().getTipo(), ua.getPerfil()))
                .toList();
        PerfilSesion activo = lista.stream()
                .filter(p -> p.ambitoId().equals(sesion.ambitoId()))
                .findFirst().orElse(null);
        return new SesionResponse(
                new UsuarioSesion(usuario.getId(), usuario.getNombre(), usuario.getUsuario(), usuario.getEmail(),
                        usuario.isEsAdminTecnico()),
                lista, activo);
    }
}
