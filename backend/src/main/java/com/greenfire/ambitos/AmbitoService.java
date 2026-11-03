package com.greenfire.ambitos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.dto.AmbitoEdicionAdminRequest;
import com.greenfire.ambitos.dto.AmbitoRequest;
import com.greenfire.ambitos.dto.AmbitoResponse;
import com.greenfire.ambitos.dto.ContactoLocalRequest;
import com.greenfire.ambitos.dto.LocalPublicoResponse;
import com.greenfire.auditoria.Auditor;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.fiscal.EntidadFiscal;
import com.greenfire.fiscal.EntidadFiscalRepository;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

@Service
@Transactional
public class AmbitoService {

    private static final String ENTIDAD = "ambito";
    private static final String MENSAJE_CENTRAL = "Es el local central: para %s, primero marcá otro local como central.";

    private final AmbitoRepository ambitos;
    private final EntidadFiscalRepository entidades;
    private final Acceso acceso;
    private final Auditor auditor;

    public AmbitoService(AmbitoRepository ambitos, EntidadFiscalRepository entidades, Acceso acceso, Auditor auditor) {
        this.ambitos = ambitos;
        this.entidades = entidades;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    // --- Administrador técnico ---

    @Transactional(readOnly = true)
    public List<AmbitoResponse> listar() {
        acceso.exigirAdminTecnico();
        return ambitos.findAllByOrderByTipoAscNombreAsc().stream().map(AmbitoResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public AmbitoResponse obtener(Long id) {
        acceso.exigirAdminTecnico();
        return AmbitoResponse.de(buscar(id));
    }

    public AmbitoResponse crear(AmbitoRequest req) {
        acceso.exigirAdminTecnico();
        if (req.tipo() == TipoAmbito.fabrica && ambitos.existsByTipo(TipoAmbito.fabrica)) {
            throw new ReglaNegocioException("Ya existe la fábrica: solo puede haber una.");
        }
        validarVisibleWeb(req.tipo(), req.visibleWeb());
        EntidadFiscal entidad = entidades.findById(req.entidadFiscalId())
                .orElseThrow(() -> new NoEncontradoException("No existe la entidad fiscal indicada."));

        Ambito ambito = new Ambito();
        ambito.setTipo(req.tipo());
        ambito.setNombre(req.nombre());
        ambito.setEntidadFiscal(entidad);
        ambito.setDireccion(req.direccion());
        ambito.setTelefono(req.telefono());
        ambito.setEmail(req.email());
        ambito.setHorarios(req.horarios());
        ambito.setVisibleWeb(req.visibleWeb());
        ambitos.save(ambito);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("tipo", ambito.getTipo());
        detalle.put("nombre", ambito.getNombre());
        detalle.put("entidadFiscalId", entidad.getId());
        detalle.put("visibleWeb", ambito.isVisibleWeb());
        auditor.registrar("crear", ENTIDAD, ambito.getId(), List.of(ambito.getId()), detalle);
        return AmbitoResponse.de(ambito);
    }

    public AmbitoResponse editar(Long id, AmbitoEdicionAdminRequest req) {
        acceso.exigirAdminTecnico();
        Ambito a = buscar(id);
        validarVisibleWeb(a.getTipo(), req.visibleWeb());
        if (!req.visibleWeb()) {
            validarNoEsCentral(a, "ocultarlo de la web");
        }
        Cambios c = new Cambios();
        a.setNombre(c.registrar("nombre", a.getNombre(), req.nombre()));
        a.setDireccion(c.registrar("direccion", a.getDireccion(), req.direccion()));
        a.setTelefono(c.registrar("telefono", a.getTelefono(), req.telefono()));
        a.setEmail(c.registrar("email", a.getEmail(), req.email()));
        a.setHorarios(c.registrar("horarios", a.getHorarios(), req.horarios()));
        a.setVisibleWeb(c.registrar("visibleWeb", a.isVisibleWeb(), req.visibleWeb()));
        auditor.registrarCambios("editar", ENTIDAD, id, List.of(id), c);
        return AmbitoResponse.de(a);
    }

    public AmbitoResponse cambiarActivo(Long id, boolean activo) {
        acceso.exigirAdminTecnico();
        Ambito a = buscar(id);
        if (!activo) {
            validarNoEsCentral(a, "desactivarlo");
        }
        Cambios c = new Cambios();
        a.setActivo(c.registrar("activo", a.isActivo(), activo));
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, List.of(id), c);
        return AmbitoResponse.de(a);
    }

    /**
     * Marca el local central y desmarca al anterior, en la misma transacción; cada uno queda auditado con su
     * ámbito. La base exige que sea un local activo y visible en la web y que no haya dos (V8); acá se valida
     * antes para dar un mensaje claro.
     */
    public AmbitoResponse marcarCentral(Long id) {
        acceso.exigirAdminTecnico();
        Ambito nuevo = buscar(id);
        if (nuevo.isEsCentral()) {
            return AmbitoResponse.de(nuevo);
        }
        if (nuevo.getTipo() != TipoAmbito.local || !nuevo.isActivo() || !nuevo.isVisibleWeb()) {
            throw new ReglaNegocioException("El local central tiene que ser un local activo y visible en la web.");
        }
        ambitos.findByEsCentralTrue().ifPresent(anterior -> {
            Cambios c = new Cambios();
            anterior.setEsCentral(c.registrar("esCentral", true, false));
            ambitos.flush(); // el índice único no deja marcar el nuevo antes de desmarcar el anterior
            auditor.registrarCambios("desmarcar_central", ENTIDAD, anterior.getId(), List.of(anterior.getId()), c);
        });
        Cambios c = new Cambios();
        nuevo.setEsCentral(c.registrar("esCentral", false, true));
        ambitos.flush();
        auditor.registrarCambios("marcar_central", ENTIDAD, id, List.of(id), c);
        return AmbitoResponse.de(nuevo);
    }

    // --- Usuarios de un ámbito ---

    @Transactional(readOnly = true)
    public AmbitoResponse actual() {
        Sesion sesion = acceso.exigirAmbito();
        return AmbitoResponse.de(buscar(sesion.ambitoId()));
    }

    /** El dueño de local edita los datos de contacto del local en el que está trabajando. */
    public AmbitoResponse editarContacto(ContactoLocalRequest req) {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local);
        Ambito a = buscar(sesion.ambitoId());
        Cambios c = new Cambios();
        a.setDireccion(c.registrar("direccion", a.getDireccion(), req.direccion()));
        a.setHorarios(c.registrar("horarios", a.getHorarios(), req.horarios()));
        a.setTelefono(c.registrar("telefono", a.getTelefono(), req.telefono()));
        a.setEmail(c.registrar("email", a.getEmail(), req.email()));
        auditor.registrarCambios("editar_contacto", ENTIDAD, a.getId(), List.of(a.getId()), c);
        return AmbitoResponse.de(a);
    }

    // --- Sitio público ---

    @Transactional(readOnly = true)
    public List<LocalPublicoResponse> localesPublicos() {
        return ambitos.findByTipoAndVisibleWebTrueAndActivoTrueOrderByNombre(TipoAmbito.local).stream()
                .map(LocalPublicoResponse::de).toList();
    }

    private void validarVisibleWeb(TipoAmbito tipo, boolean visibleWeb) {
        if (visibleWeb && tipo == TipoAmbito.fabrica) {
            throw new ReglaNegocioException("La fábrica no se muestra en la web: solo los locales.");
        }
    }

    private void validarNoEsCentral(Ambito a, String accion) {
        if (a.isEsCentral()) {
            throw new ReglaNegocioException(MENSAJE_CENTRAL.formatted(accion));
        }
    }

    private Ambito buscar(Long id) {
        return ambitos.findById(id).orElseThrow(() -> new NoEncontradoException("No existe el ámbito indicado."));
    }
}
