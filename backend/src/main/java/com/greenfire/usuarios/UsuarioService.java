package com.greenfire.usuarios;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.comun.AccesoDenegadoException;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.seguridad.SesionHttp;
import com.greenfire.usuarios.dto.DatosUsuarioRequest;
import com.greenfire.usuarios.dto.DuenoRequest;
import com.greenfire.usuarios.dto.EmpleadoRequest;
import com.greenfire.usuarios.dto.PerfilRequest;
import com.greenfire.usuarios.dto.UsuarioLocalResponse;
import com.greenfire.usuarios.dto.UsuarioResponse;

/**
 * Quién crea a quién: el administrador técnico crea los dueños (de fábrica y de local); el dueño de local
 * crea solo empleados de su local; el dueño de fábrica no crea usuarios. Los usuarios no se borran: se desactivan.
 */
@Service
@Transactional
public class UsuarioService {

    private static final String ENTIDAD = "usuario";

    private final UsuarioRepository usuarios;
    private final UsuarioAmbitoRepository perfiles;
    private final AmbitoRepository ambitos;
    private final PasswordEncoder encoder;
    private final SesionHttp sesionHttp;
    private final Acceso acceso;
    private final Auditor auditor;

    public UsuarioService(UsuarioRepository usuarios, UsuarioAmbitoRepository perfiles, AmbitoRepository ambitos,
            PasswordEncoder encoder, SesionHttp sesionHttp, Acceso acceso, Auditor auditor) {
        this.usuarios = usuarios;
        this.perfiles = perfiles;
        this.ambitos = ambitos;
        this.encoder = encoder;
        this.sesionHttp = sesionHttp;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    // --- Administrador técnico ---

    @Transactional(readOnly = true)
    public List<UsuarioResponse> listar() {
        acceso.exigirAdminTecnico();
        return usuarios.findByEsAdminTecnicoFalseOrderByNombre().stream()
                .map(u -> UsuarioResponse.de(u, perfiles.deUsuario(u.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public UsuarioResponse obtener(Long id) {
        acceso.exigirAdminTecnico();
        Usuario u = buscarGestionable(id);
        return UsuarioResponse.de(u, perfiles.deUsuario(id));
    }

    public UsuarioResponse crearDueno(DuenoRequest req) {
        Sesion sesion = acceso.exigirAdminTecnico();
        validarPerfilDeDueno(req.perfil());
        Ambito ambito = ambitoActivo(req.ambitoId());
        validarPerfilParaAmbito(req.perfil(), ambito);
        Usuario u = nuevoUsuario(req.nombre(), req.usuario(), req.email(), req.password());
        UsuarioAmbito ua = asignar(u, ambito, req.perfil(), sesion.usuarioId());
        auditarAlta(u, ua);
        return UsuarioResponse.de(u, List.of(ua));
    }

    /** Por ejemplo, un dueño de fábrica que también es dueño del local central. */
    public UsuarioResponse agregarPerfil(Long usuarioId, PerfilRequest req) {
        Sesion sesion = acceso.exigirAdminTecnico();
        validarPerfilDeDueno(req.perfil());
        Usuario u = buscarGestionable(usuarioId);
        Ambito ambito = ambitoActivo(req.ambitoId());
        validarPerfilParaAmbito(req.perfil(), ambito);
        if (perfiles.existsByUsuarioIdAndAmbitoId(usuarioId, ambito.getId())) {
            throw new ReglaNegocioException("El usuario ya tiene un perfil en ese ámbito.");
        }
        asignar(u, ambito, req.perfil(), sesion.usuarioId());
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("ambitoId", ambito.getId());
        detalle.put("perfil", req.perfil());
        auditor.registrar("agregar_perfil", ENTIDAD, usuarioId, List.of(ambito.getId()), detalle);
        return UsuarioResponse.de(u, perfiles.deUsuario(usuarioId));
    }

    public UsuarioResponse cambiarActivoPerfil(Long usuarioId, Long ambitoId, boolean activo) {
        acceso.exigirAdminTecnico();
        buscarGestionable(usuarioId);
        UsuarioAmbito ua = perfiles.deUsuarioEnAmbito(usuarioId, ambitoId)
                .orElseThrow(() -> new NoEncontradoException("El usuario no tiene perfil en ese ámbito."));
        cambiarActivo(ua, activo);
        return UsuarioResponse.de(ua.getUsuario(), perfiles.deUsuario(usuarioId));
    }

    public UsuarioResponse editar(Long id, DatosUsuarioRequest req) {
        acceso.exigirAdminTecnico();
        Usuario u = buscarGestionable(id);
        editarDatos(u, req);
        return UsuarioResponse.de(u, perfiles.deUsuario(id));
    }

    public UsuarioResponse cambiarActivo(Long id, boolean activo) {
        acceso.exigirAdminTecnico();
        Usuario u = buscarGestionable(id);
        Cambios c = new Cambios();
        u.setActivo(c.registrar("activo", u.isActivo(), activo));
        if (!activo) {
            sesionHttp.cerrarTodas(id);
        }
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, ambitosDe(id), c);
        return UsuarioResponse.de(u, perfiles.deUsuario(id));
    }

    /** En producción no hay mail: el administrador técnico asigna una contraseña nueva. */
    public void restablecerPassword(Long id, String password) {
        acceso.exigirAdminTecnico();
        Usuario u = buscarGestionable(id);
        u.setPasswordHash(encoder.encode(password));
        u.setIntentosFallidos(0);
        u.setBloqueadoHasta(null);
        u.setResetTokenHash(null);
        u.setResetTokenVence(null);
        sesionHttp.cerrarTodas(id);
        auditor.registrar("restablecer_password", ENTIDAD, id, ambitosDe(id), Map.of());
    }

    // --- Dueño de local ---

    @Transactional(readOnly = true)
    public List<UsuarioLocalResponse> listarDeMiLocal() {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local);
        return perfiles.deAmbito(sesion.ambitoId()).stream().map(UsuarioLocalResponse::de).toList();
    }

    public UsuarioLocalResponse crearEmpleado(EmpleadoRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local);
        Ambito ambito = ambitoActivo(sesion.ambitoId());
        Usuario u = nuevoUsuario(req.nombre(), req.usuario(), req.email(), req.password());
        UsuarioAmbito ua = asignar(u, ambito, Perfil.empleado_local, sesion.usuarioId());
        auditarAlta(u, ua);
        return UsuarioLocalResponse.de(ua);
    }

    public UsuarioLocalResponse editarEmpleado(Long usuarioId, DatosUsuarioRequest req) {
        UsuarioAmbito ua = empleadoDeMiLocal(usuarioId);
        editarDatos(ua.getUsuario(), req);
        return UsuarioLocalResponse.de(ua);
    }

    /** Desactiva el perfil del empleado en este local; la cuenta no se borra. */
    public UsuarioLocalResponse cambiarActivoEmpleado(Long usuarioId, boolean activo) {
        UsuarioAmbito ua = empleadoDeMiLocal(usuarioId);
        cambiarActivo(ua, activo);
        return UsuarioLocalResponse.de(ua);
    }

    // --- Reglas comunes ---

    private UsuarioAmbito empleadoDeMiLocal(Long usuarioId) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local);
        UsuarioAmbito ua = perfiles.deUsuarioEnAmbito(usuarioId, sesion.ambitoId())
                .orElseThrow(() -> new NoEncontradoException("No existe ese usuario en tu local."));
        if (ua.getPerfil() != Perfil.empleado_local) {
            throw new AccesoDenegadoException();
        }
        return ua;
    }

    private void validarPerfilDeDueno(Perfil perfil) {
        if (perfil == Perfil.empleado_local) {
            throw new ReglaNegocioException("Los empleados los crea el dueño de su local.");
        }
    }

    private void validarPerfilParaAmbito(Perfil perfil, Ambito ambito) {
        boolean esFabrica = ambito.getTipo() == TipoAmbito.fabrica;
        if ((perfil == Perfil.dueno_fabrica) != esFabrica) {
            throw new ReglaNegocioException(esFabrica
                    ? "En la fábrica el único perfil es dueño de fábrica."
                    : "El perfil dueño de fábrica solo se asigna en la fábrica.");
        }
    }

    private Ambito ambitoActivo(Long ambitoId) {
        Ambito ambito = ambitos.findById(ambitoId)
                .orElseThrow(() -> new NoEncontradoException("No existe el ámbito indicado."));
        if (!ambito.isActivo()) {
            throw new ReglaNegocioException("El ámbito está desactivado.");
        }
        return ambito;
    }

    private Usuario nuevoUsuario(String nombre, String usuario, String email, String password) {
        if (usuarios.existeUsuario(usuario)) {
            throw new ReglaNegocioException("Ya existe un usuario con ese nombre de usuario.");
        }
        if (usuarios.existeEmail(email.trim())) {
            throw new ReglaNegocioException("Ya existe un usuario con ese email.");
        }
        Usuario u = new Usuario();
        u.setNombre(nombre.trim());
        u.setUsuario(usuario);
        u.setEmail(email.trim());
        u.setPasswordHash(encoder.encode(password));
        return usuarios.save(u);
    }

    private UsuarioAmbito asignar(Usuario u, Ambito ambito, Perfil perfil, Long creadoPor) {
        UsuarioAmbito ua = new UsuarioAmbito();
        ua.setUsuario(u);
        ua.setAmbito(ambito);
        ua.setPerfil(perfil);
        ua.setCreadoPorUsuarioId(creadoPor);
        return perfiles.save(ua);
    }

    private void editarDatos(Usuario u, DatosUsuarioRequest req) {
        if (usuarios.existeEmailEnOtro(req.email().trim(), u.getId())) {
            throw new ReglaNegocioException("Ya existe un usuario con ese email.");
        }
        Cambios c = new Cambios();
        u.setNombre(c.registrar("nombre", u.getNombre(), req.nombre().trim()));
        u.setEmail(c.registrar("email", u.getEmail(), req.email().trim()));
        auditor.registrarCambios("editar", ENTIDAD, u.getId(), ambitosDe(u.getId()), c);
    }

    private void cambiarActivo(UsuarioAmbito ua, boolean activo) {
        Cambios c = new Cambios();
        ua.setActivo(c.registrar("activo", ua.isActivo(), activo));
        Map<String, Object> detalle = new LinkedHashMap<>(c.comoMapa());
        detalle.put("ambitoId", ua.getAmbito().getId());
        detalle.put("perfil", ua.getPerfil());
        if (!c.vacio()) {
            auditor.registrar(activo ? "activar_perfil" : "desactivar_perfil", ENTIDAD, ua.getUsuario().getId(),
                    List.of(ua.getAmbito().getId()), detalle);
        }
    }

    private void auditarAlta(Usuario u, UsuarioAmbito ua) {
        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("nombre", u.getNombre());
        detalle.put("usuario", u.getUsuario());
        detalle.put("email", u.getEmail());
        detalle.put("ambitoId", ua.getAmbito().getId());
        detalle.put("perfil", ua.getPerfil());
        auditor.registrar("crear", ENTIDAD, u.getId(), List.of(ua.getAmbito().getId()), detalle);
    }

    /** Un cambio en la cuenta toca a todos los ámbitos en los que el usuario tiene perfil. */
    private List<Long> ambitosDe(Long usuarioId) {
        return perfiles.deUsuario(usuarioId).stream().map(ua -> ua.getAmbito().getId()).toList();
    }

    /** Los administradores técnicos no se gestionan desde la API. */
    private Usuario buscarGestionable(Long id) {
        return usuarios.findById(id).filter(u -> !u.isEsAdminTecnico())
                .orElseThrow(() -> new NoEncontradoException("No existe el usuario indicado."));
    }
}
