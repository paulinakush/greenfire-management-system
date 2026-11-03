package com.greenfire.fiscal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.auditoria.Auditor;
import com.greenfire.comun.Cambios;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.fiscal.dto.PuntoVentaEdicionRequest;
import com.greenfire.fiscal.dto.PuntoVentaRequest;
import com.greenfire.fiscal.dto.PuntoVentaResponse;
import com.greenfire.seguridad.Acceso;

/** Puntos de venta: solo los administra el administrador técnico. */
@Service
@Transactional
public class PuntoVentaService {

    private static final String ENTIDAD = "punto_venta";

    private final PuntoVentaRepository puntosVenta;
    private final AmbitoRepository ambitos;
    private final Acceso acceso;
    private final Auditor auditor;

    public PuntoVentaService(PuntoVentaRepository puntosVenta, AmbitoRepository ambitos, Acceso acceso,
            Auditor auditor) {
        this.puntosVenta = puntosVenta;
        this.ambitos = ambitos;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    @Transactional(readOnly = true)
    public List<PuntoVentaResponse> listar(Long ambitoId) {
        acceso.exigirAdminTecnico();
        List<PuntoVenta> lista = ambitoId == null
                ? puntosVenta.findAllByOrderByEntidadFiscalIdAscNumeroAsc()
                : puntosVenta.findByAmbitoIdOrderByNumero(ambitoId);
        return lista.stream().map(PuntoVentaResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public PuntoVentaResponse obtener(Long id) {
        acceso.exigirAdminTecnico();
        return PuntoVentaResponse.de(buscar(id));
    }

    public PuntoVentaResponse crear(PuntoVentaRequest req) {
        acceso.exigirAdminTecnico();
        Ambito ambito = ambitos.findById(req.ambitoId())
                .orElseThrow(() -> new NoEncontradoException("No existe el ámbito indicado."));
        EntidadFiscal entidad = ambito.getEntidadFiscal();
        if (puntosVenta.existsByEntidadFiscalIdAndNumero(entidad.getId(), req.numero())) {
            throw new ReglaNegocioException("La entidad fiscal ya tiene un punto de venta con ese número.");
        }
        validarComprobantes(entidad, req.comprobantes());

        PuntoVenta p = new PuntoVenta();
        p.setAmbito(ambito);
        p.setNumero(req.numero());
        p.setDescripcion(req.descripcion());
        p.setComprobantes(req.comprobantes());
        puntosVenta.save(p);

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("ambitoId", ambito.getId());
        detalle.put("entidadFiscalId", entidad.getId());
        detalle.put("numero", p.getNumero());
        detalle.put("comprobantes", describir(p.getComprobantes()));
        auditor.registrar("crear", ENTIDAD, p.getId(), List.of(ambito.getId()), detalle);
        return PuntoVentaResponse.de(p);
    }

    public PuntoVentaResponse editar(Long id, PuntoVentaEdicionRequest req) {
        acceso.exigirAdminTecnico();
        PuntoVenta p = buscar(id);
        if (puntosVenta.existsByEntidadFiscalIdAndNumeroAndIdNot(p.getEntidadFiscal().getId(), req.numero(), id)) {
            throw new ReglaNegocioException("La entidad fiscal ya tiene un punto de venta con ese número.");
        }
        validarComprobantes(p.getEntidadFiscal(), req.comprobantes());

        Cambios c = new Cambios();
        p.setNumero(c.registrar("numero", p.getNumero(), req.numero()));
        p.setDescripcion(c.registrar("descripcion", p.getDescripcion(), req.descripcion()));
        c.registrar("comprobantes", describir(p.getComprobantes()), describir(req.comprobantes()));
        p.setComprobantes(req.comprobantes());
        auditor.registrarCambios("editar", ENTIDAD, id, List.of(p.getAmbito().getId()), c);
        return PuntoVentaResponse.de(p);
    }

    public PuntoVentaResponse cambiarActivo(Long id, boolean activo) {
        acceso.exigirAdminTecnico();
        PuntoVenta p = buscar(id);
        Cambios c = new Cambios();
        p.setActivo(c.registrar("activo", p.isActivo(), activo));
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, List.of(p.getAmbito().getId()),
                c);
        return PuntoVentaResponse.de(p);
    }

    private void validarComprobantes(EntidadFiscal entidad, Set<ComprobantePermitido> comprobantes) {
        List<String> invalidos = comprobantes.stream()
                .filter(cp -> !ReglasComprobante.permitido(entidad.getCondicionIva(), cp))
                .map(cp -> cp.tipo() + " " + cp.letra())
                .toList();
        if (!invalidos.isEmpty()) {
            throw new ReglaNegocioException("Una entidad " + entidad.getCondicionIva()
                    + " no puede emitir: " + String.join(", ", invalidos) + ".");
        }
    }

    /** Representación ordenada para comparar y auditar el conjunto de comprobantes. */
    private Set<String> describir(Set<ComprobantePermitido> comprobantes) {
        Set<String> resultado = new TreeSet<>();
        comprobantes.forEach(cp -> resultado.add(cp.tipo() + " " + cp.letra()));
        return resultado;
    }

    private PuntoVenta buscar(Long id) {
        return puntosVenta.findById(id)
                .orElseThrow(() -> new NoEncontradoException("No existe el punto de venta indicado."));
    }
}
