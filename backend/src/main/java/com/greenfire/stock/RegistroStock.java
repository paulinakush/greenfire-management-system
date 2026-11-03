package com.greenfire.stock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.usuarios.UsuarioRepository;

import jakarta.persistence.EntityManager;

/**
 * Punto único para registrar movimientos de stock, como {@link com.greenfire.auditoria.Auditor} para la auditoría.
 * Lo usan los ajustes y, después, los conteos, las entregas, las ventas, las compras y la producción. No valida
 * permisos: eso lo hace cada servicio antes de llamarlo.
 * <p>
 * Corre siempre dentro de la transacción de la operación (MANDATORY): si un renglón falla, no se aplica ninguno.
 * Antes de una salida bloquea la fila de stock (SELECT ... FOR UPDATE) y valida que alcance, para devolver un
 * mensaje claro; si dos operaciones sacan a la vez el último artículo, la segunda espera a que termine la primera,
 * ve la cantidad ya descontada y falla. La base sigue siendo la garantía final: el CHECK de stock_articulos.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class RegistroStock {

    /** Un renglón de una operación. {@code loteId} queda null hasta la etapa 4. */
    public record Renglon(Articulo articulo, UbicacionStock ubicacion, BigDecimal cantidad, Long loteId) {

        public Renglon(Articulo articulo, UbicacionStock ubicacion, BigDecimal cantidad) {
            this(articulo, ubicacion, cantidad, null);
        }
    }

    /** Orden fijo de bloqueo, para que dos operaciones con los mismos renglones no se bloqueen entre sí. */
    private static final Comparator<Renglon> ORDEN_DE_BLOQUEO = Comparator
            .<Renglon, Long>comparing(r -> r.articulo().getId())
            .thenComparing(r -> r.ubicacion().getId());

    private final MovimientoStockRepository movimientos;
    private final UsuarioRepository usuarios;
    private final EntityManager em;

    public RegistroStock(MovimientoStockRepository movimientos, UsuarioRepository usuarios, EntityManager em) {
        this.movimientos = movimientos;
        this.usuarios = usuarios;
        this.em = em;
    }

    /** Registra los movimientos, en el orden de bloqueo, y los devuelve en ese orden. */
    public List<MovimientoStock> registrar(TipoMovimientoStock tipo, List<Renglon> renglones, String motivo,
            String observaciones, Long usuarioId) {
        if (tipo == TipoMovimientoStock.conteo) {
            throw new IllegalArgumentException("Los movimientos de conteo se registran con registrarConteo.");
        }
        return registrar(tipo, renglones, motivo, observaciones, usuarioId, null);
    }

    /** Las diferencias de un conteo, como movimientos de tipo conteo que apuntan a él. */
    public List<MovimientoStock> registrarConteo(ConteoStock conteo, List<Renglon> renglones, String motivo,
            Long usuarioId) {
        return registrar(TipoMovimientoStock.conteo, renglones, motivo, null, usuarioId, conteo);
    }

    /**
     * Bloquea hasta el final de la transacción las filas de stock de los artículos en la ubicación, en el mismo
     * orden que {@link #registrar}, y devuelve la cantidad de cada uno. Si un artículo todavía no tiene fila, la
     * crea con cantidad cero para poder bloquearla: así nadie puede registrarle un primer movimiento mientras tanto.
     */
    public Map<Long, BigDecimal> bloquearCantidades(UbicacionStock ubicacion, List<Articulo> articulos) {
        List<Articulo> ordenados = articulos.stream().map(a -> new Renglon(a, ubicacion, BigDecimal.ONE))
                .sorted(ORDEN_DE_BLOQUEO).map(Renglon::articulo).toList();
        Map<Long, BigDecimal> cantidades = new LinkedHashMap<>();
        for (Articulo a : ordenados) {
            em.createNativeQuery("""
                            insert into stock_articulos (articulo_id, ubicacion_stock_id) values (?1, ?2)
                            on conflict (articulo_id, ubicacion_stock_id) do nothing
                            """)
                    .setParameter(1, a.getId())
                    .setParameter(2, ubicacion.getId())
                    .executeUpdate();
            cantidades.put(a.getId(), cantidadBloqueada(a.getId(), ubicacion.getId()));
        }
        return cantidades;
    }

    private List<MovimientoStock> registrar(TipoMovimientoStock tipo, List<Renglon> renglones, String motivo,
            String observaciones, Long usuarioId, ConteoStock conteo) {
        if (renglones.isEmpty()) {
            throw new ReglaNegocioException("La operación no tiene renglones.");
        }
        renglones.forEach(RegistroStock::validar);
        List<Renglon> ordenados = renglones.stream().sorted(ORDEN_DE_BLOQUEO).toList();

        List<MovimientoStock> registrados = new ArrayList<>();
        for (Renglon r : ordenados) {
            if (r.cantidad().signum() < 0) {
                BigDecimal actual = cantidadBloqueada(r.articulo().getId(), r.ubicacion().getId());
                if (actual.add(r.cantidad()).signum() < 0) {
                    throw new StockInsuficienteException("'%s' en '%s' tiene %s y se quieren sacar %s.".formatted(
                            r.articulo().getNombre(), r.ubicacion().getNombre(), texto(actual),
                            texto(r.cantidad().negate())));
                }
            }
            registrados.add(movimientos.save(new MovimientoStock(r.articulo(), r.ubicacion(), tipo, r.cantidad(),
                    r.loteId(), usuarios.getReferenceById(usuarioId), motivo, observaciones, conteo)));
        }
        return registrados;
    }

    private static void validar(Renglon r) {
        if (r.cantidad().signum() == 0) {
            throw new ReglaNegocioException("La cantidad de '" + r.articulo().getNombre() + "' no puede ser cero.");
        }
        if (r.articulo().getUnidadMedida() == UnidadMedida.unidad && r.cantidad().stripTrailingZeros().scale() > 0) {
            throw new ReglaNegocioException("'" + r.articulo().getNombre()
                    + "' se mide en unidades: la cantidad debe ser entera.");
        }
        if (!r.articulo().visiblePara(r.ubicacion().getAmbito())) {
            throw new ReglaNegocioException("La ubicación '" + r.ubicacion().getNombre()
                    + "' no puede tener stock de '" + r.articulo().getNombre() + "'.");
        }
    }

    /** Bloquea la fila hasta el final de la transacción. Sin fila, todavía no hubo movimientos: cantidad cero. */
    private BigDecimal cantidadBloqueada(Long articuloId, Long ubicacionId) {
        List<?> fila = em.createNativeQuery("""
                        select cantidad_actual from stock_articulos
                        where articulo_id = ?1 and ubicacion_stock_id = ?2 for update
                        """)
                .setParameter(1, articuloId)
                .setParameter(2, ubicacionId)
                .getResultList();
        return fila.isEmpty() ? BigDecimal.ZERO : (BigDecimal) fila.get(0);
    }

    private static String texto(BigDecimal cantidad) {
        return cantidad.stripTrailingZeros().toPlainString();
    }
}
