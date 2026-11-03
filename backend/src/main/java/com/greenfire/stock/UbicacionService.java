package com.greenfire.stock;

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
import com.greenfire.seguridad.Acceso;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.stock.dto.UbicacionRequest;
import com.greenfire.stock.dto.UbicacionResponse;

/**
 * Ubicaciones de stock del ámbito de la sesión. Las administra su dueño; el empleado de local consulta las activas.
 * Nadie ve las de otro ámbito.
 */
@Service
@Transactional
public class UbicacionService {

    private static final String ENTIDAD = "ubicacion_stock";
    private static final String NO_EXISTE = "No existe la ubicación indicada.";

    private final UbicacionStockRepository ubicaciones;
    private final StockArticuloRepository stock;
    private final AmbitoRepository ambitos;
    private final Acceso acceso;
    private final Auditor auditor;

    public UbicacionService(UbicacionStockRepository ubicaciones, StockArticuloRepository stock,
            AmbitoRepository ambitos, Acceso acceso, Auditor auditor) {
        this.ubicaciones = ubicaciones;
        this.stock = stock;
        this.ambitos = ambitos;
        this.acceso = acceso;
        this.auditor = auditor;
    }

    @Transactional(readOnly = true)
    public List<UbicacionResponse> listar() {
        Sesion sesion = acceso.exigirAmbito();
        List<UbicacionStock> lista = sesion.perfil() == Perfil.empleado_local
                ? ubicaciones.findByAmbitoIdAndActivoTrueOrderByNombre(sesion.ambitoId())
                : ubicaciones.findByAmbitoIdOrderByNombre(sesion.ambitoId());
        return lista.stream().map(UbicacionResponse::de).toList();
    }

    public UbicacionResponse crear(UbicacionRequest req) {
        Ambito ambito = ambitoDe(exigirDueno());
        String nombre = req.nombre().trim();
        if (ubicaciones.existsByAmbitoIdAndNombreIgnoreCase(ambito.getId(), nombre)) {
            throw new ReglaNegocioException("Ya existe una ubicación con ese nombre.");
        }
        UbicacionStock u = new UbicacionStock();
        u.setAmbito(ambito);
        u.setNombre(nombre);
        ubicaciones.save(u);
        auditor.registrar("crear", ENTIDAD, u.getId(), List.of(ambito.getId()), Map.of("nombre", nombre));
        return UbicacionResponse.de(u);
    }

    public UbicacionResponse editar(Long id, UbicacionRequest req) {
        Ambito ambito = ambitoDe(exigirDueno());
        UbicacionStock u = buscar(id, ambito);
        String nombre = req.nombre().trim();
        if (ubicaciones.existsByAmbitoIdAndNombreIgnoreCaseAndIdNot(ambito.getId(), nombre, id)) {
            throw new ReglaNegocioException("Ya existe una ubicación con ese nombre.");
        }
        Cambios c = new Cambios();
        u.setNombre(c.registrar("nombre", u.getNombre(), nombre));
        auditor.registrarCambios("editar", ENTIDAD, id, List.of(ambito.getId()), c);
        return UbicacionResponse.de(u);
    }

    /** Una ubicación con stock no se desactiva: ese stock dejaría de verse. */
    public UbicacionResponse cambiarActivo(Long id, boolean activo) {
        Ambito ambito = ambitoDe(exigirDueno());
        UbicacionStock u = buscar(id, ambito);
        if (!activo && u.isActivo() && stock.tieneStock(id)) {
            throw new ReglaNegocioException("La ubicación tiene stock: ajustalo o movelo antes de desactivarla.");
        }
        Cambios c = new Cambios();
        u.setActivo(c.registrar("activo", u.isActivo(), activo));
        auditor.registrarCambios(activo ? "activar" : "desactivar", ENTIDAD, id, List.of(ambito.getId()), c);
        return UbicacionResponse.de(u);
    }

    // --- Usados también por StockService ---

    Sesion exigirDueno() {
        return acceso.exigirPerfil(Perfil.dueno_fabrica, Perfil.dueno_local);
    }

    Ambito ambitoDe(Sesion sesion) {
        return ambitos.findById(sesion.ambitoId()).orElseThrow(() -> new NoEncontradoException("No existe el ámbito."));
    }

    /** Una ubicación de otro ámbito responde igual que una inexistente. */
    UbicacionStock buscar(Long id, Ambito ambito) {
        return ubicaciones.findById(id).filter(u -> u.esDe(ambito))
                .orElseThrow(() -> new NoEncontradoException(NO_EXISTE));
    }

    /** Para operar: además de ser del ámbito, tiene que estar activa. */
    UbicacionStock buscarActiva(Long id, Ambito ambito) {
        UbicacionStock u = buscar(id, ambito);
        if (!u.isActivo()) {
            throw new ReglaNegocioException("La ubicación '" + u.getNombre() + "' está desactivada.");
        }
        return u;
    }
}
