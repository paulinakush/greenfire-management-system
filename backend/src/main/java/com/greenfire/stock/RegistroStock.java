package com.greenfire.stock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        if (renglones.isEmpty()) {
            throw new ReglaNegocioException("La operación no tiene renglones.");
        }
        renglones.forEach(RegistroStock::validar);
        List<Renglon> ordenados = renglones.stream().sorted(ORDEN_DE_BLOQUEO).toList();

        List<MovimientoStock> registrados = new ArrayList<>();
        for (Renglon r : ordenados) {
            if (r.cantidad().signum() < 0) {
                BigDecimal actual = cantidadBloqueada(r);
                if (actual.add(r.cantidad()).signum() < 0) {
                    throw new StockInsuficienteException("'%s' en '%s' tiene %s y se quieren sacar %s.".formatted(
                            r.articulo().getNombre(), r.ubicacion().getNombre(), texto(actual),
                            texto(r.cantidad().negate())));
                }
            }
            registrados.add(movimientos.save(new MovimientoStock(r.articulo(), r.ubicacion(), tipo, r.cantidad(),
                    r.loteId(), usuarios.getReferenceById(usuarioId), motivo, observaciones)));
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
    private BigDecimal cantidadBloqueada(Renglon r) {
        List<?> fila = em.createNativeQuery("""
                        select cantidad_actual from stock_articulos
                        where articulo_id = ?1 and ubicacion_stock_id = ?2 for update
                        """)
                .setParameter(1, r.articulo().getId())
                .setParameter(2, r.ubicacion().getId())
                .getResultList();
        return fila.isEmpty() ? BigDecimal.ZERO : (BigDecimal) fila.get(0);
    }

    private static String texto(BigDecimal cantidad) {
        return cantidad.stripTrailingZeros().toPlainString();
    }
}
