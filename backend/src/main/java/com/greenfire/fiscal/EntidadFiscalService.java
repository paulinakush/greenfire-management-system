package com.greenfire.fiscal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.auditoria.Auditor;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.fiscal.dto.EntidadFiscalLocalResponse;
import com.greenfire.fiscal.dto.EntidadFiscalRequest;
import com.greenfire.fiscal.dto.EntidadFiscalResponse;
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;

/**
 * Entidades fiscales: solo las administra el administrador técnico.
 * El dueño de local puede consultar, sin editar, la de su propio local.
 */
@Service
@Transactional
public class EntidadFiscalService {

    private static final String ENTIDAD = "entidad_fiscal";

    private final EntidadFiscalRepository entidades;
    private final PuntoVentaRepository puntosVenta;
    private final AmbitoRepository ambitos;
    private final Acceso acceso;
    private final Auditor auditor;

    public EntidadFiscalService(EntidadFiscalRepository entidades, PuntoVentaRepository puntosVenta,
            AmbitoRepository ambitos, Acceso acceso, Auditor auditor) {
        this.entidades = entidades;
        this.puntosVenta = puntosVenta;
        this.ambitos = ambitos;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    @Transactional(readOnly = true)
    public List<EntidadFiscalResponse> listar() {
        acceso.exigirAdminTecnico();
        return entidades.findAllByOrderByRazonSocial().stream().map(EntidadFiscalResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public EntidadFiscalResponse obtener(Long id) {
        acceso.exigirAdminTecnico();
        return EntidadFiscalResponse.de(buscar(id));
    }

    /** La entidad fiscal y los puntos de venta del local en el que trabaja el dueño. */
    @Transactional(readOnly = true)
    public EntidadFiscalLocalResponse deMiLocal() {
        Sesion sesion = acceso.exigirPerfil(Perfil.dueno_local);
        Ambito ambito = ambitos.findById(sesion.ambitoId())
                .orElseThrow(() -> new NoEncontradoException("No existe el ámbito indicado."));
        return EntidadFiscalLocalResponse.de(ambito.getEntidadFiscal(),
                puntosVenta.findByAmbitoIdOrderByNumero(ambito.getId()));
    }

    public EntidadFiscalResponse crear(EntidadFiscalRequest req) {
        acceso.exigirAdminTecnico();
        if (entidades.existsByCuit(req.cuit())) {
            throw new ReglaNegocioException("Ya existe una entidad fiscal con ese CUIT.");
        }
        EntidadFiscal e = new EntidadFiscal();
        e.setRazonSocial(req.razonSocial());
        e.setCuit(req.cuit());
        e.setCondicionIva(req.condicionIva());
        e.setDomicilioFiscal(req.domicilioFiscal());
        e.setArcaCertificadoRef(req.arcaCertificadoRef());
        e.setArcaCertificadoVence(req.arcaCertificadoVence());
        entidades.save(e);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("razonSocial", e.getRazonSocial());
        detalle.put("cuit", e.getCuit());
        detalle.put("condicionIva", e.getCondicionIva());
        auditor.registrar("crear", ENTIDAD, e.getId(), detalle);
        return EntidadFiscalResponse.de(e);
    }

    public EntidadFiscalResponse editar(Long id, EntidadFiscalRequest req) {
        acceso.exigirAdminTecnico();
        EntidadFiscal e = buscar(id);
        if (entidades.existsByCuitAndIdNot(req.cuit(), id)) {
            throw new ReglaNegocioException("Ya existe una entidad fiscal con ese CUIT.");
        }
        if (req.condicionIva() != e.getCondicionIva()) {
            validarPuntosVentaCompatibles(e, req.condicionIva());
        }
        Cambios c = new Cambios();
        e.setRazonSocial(c.registrar("razonSocial", e.getRazonSocial(), req.razonSocial()));
        e.setCuit(c.registrar("cuit", e.getCuit(), req.cuit()));
        e.setCondicionIva(c.registrar("condicionIva", e.getCondicionIva(), req.condicionIva()));
        e.setDomicilioFiscal(c.registrar("domicilioFiscal", e.getDomicilioFiscal(), req.domicilioFiscal()));
        e.setArcaCertificadoRef(c.registrar("arcaCertificadoRef", e.getArcaCertificadoRef(), req.arcaCertificadoRef()));
        e.setArcaCertificadoVence(
                c.registrar("arcaCertificadoVence", e.getArcaCertificadoVence(), req.arcaCertificadoVence()));
        auditor.registrarCambios("editar", ENTIDAD, id, ambitos.idsDeEntidadFiscal(id), c);
        return EntidadFiscalResponse.de(e);
    }

    public EntidadFiscalResponse cambiarActivo(Long id, boolean activo) {
        acceso.exigirAdminTecnico();
        EntidadFiscal e = buscar(id);
        Cambios c = new Cambios();
        e.setActivo(c.registrar("activo", e.isActivo(), activo));
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, ambitos.idsDeEntidadFiscal(id), c);
        return EntidadFiscalResponse.de(e);
    }

    /** Un cambio de condición frente al IVA no puede dejar puntos de venta con letras que ya no corresponden. */
    private void validarPuntosVentaCompatibles(EntidadFiscal e, CondicionIva nueva) {
        boolean incompatible = puntosVenta.findByEntidadFiscalId(e.getId()).stream()
                .flatMap(p -> p.getComprobantes().stream())
                .anyMatch(cp -> !ReglasComprobante.permitido(nueva, cp));
        if (incompatible) {
            throw new ReglaNegocioException("Hay puntos de venta con comprobantes que la nueva condición frente al IVA"
                    + " no permite emitir. Ajustalos antes de cambiarla.");
        }
    }

    private EntidadFiscal buscar(Long id) {
        return entidades.findById(id)
                .orElseThrow(() -> new NoEncontradoException("No existe la entidad fiscal indicada."));
    }
}
