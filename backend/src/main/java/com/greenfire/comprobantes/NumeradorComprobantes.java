package com.greenfire.comprobantes;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.TipoComprobante;

import jakarta.persistence.EntityManager;

/**
 * Toma el siguiente número de un tipo y letra en un punto de venta activo del ámbito, sin huecos ni repetidos.
 * <p>
 * Bloquea la fila de punto_venta_comprobantes con SELECT ... FOR UPDATE y avanza ultimo_numero, en la transacción
 * de la emisión (MANDATORY). Si dos emisiones piden número a la vez, la segunda espera a que la primera termine y
 * toma el siguiente. Si la emisión falla y se revierte, el avance se revierte con ella y el número no se consume.
 * La base es la garantía final (V12): el número solo avanza de a uno, al confirmar tiene su comprobante, y el
 * índice único de comprobantes no admite repetidos.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class NumeradorComprobantes {

    /** El punto de venta elegido y el número tomado. */
    public record Numero(Long puntoVentaId, int numero) {
    }

    private final EntityManager em;

    public NumeradorComprobantes(EntityManager em) {
        this.em = em;
    }

    /**
     * Si el ámbito tiene más de un punto de venta activo con ese tipo y letra, usa el de número más bajo. Sin
     * ninguno, falla con un mensaje para que el administrador técnico lo configure.
     */
    public Numero siguiente(Ambito ambito, TipoComprobante tipo, LetraComprobante letra) {
        List<?> filas = em.createNativeQuery("""
                        select pvc.id, pvc.punto_venta_id, pvc.ultimo_numero
                        from punto_venta_comprobantes pvc
                        join puntos_venta pv on pv.id = pvc.punto_venta_id
                        where pv.ambito_id = ?1 and pv.activo and pvc.tipo = ?2 and pvc.letra = ?3
                        order by pv.numero
                        limit 1
                        for update of pvc
                        """)
                .setParameter(1, ambito.getId())
                .setParameter(2, tipo.name())
                .setParameter(3, letra.name())
                .getResultList();
        if (filas.isEmpty()) {
            throw new ReglaNegocioException("'%s' no tiene un punto de venta activo que emita %s %s. "
                    .formatted(ambito.getNombre(), tipo.nombre().toLowerCase(), letra)
                    + "Pedile al administrador técnico que lo configure.");
        }
        Object[] fila = (Object[]) filas.get(0);
        long id = ((Number) fila[0]).longValue();
        int numero = ((Number) fila[2]).intValue() + 1;
        em.createNativeQuery("update punto_venta_comprobantes set ultimo_numero = ?1, updated_at = now() where id = ?2")
                .setParameter(1, numero)
                .setParameter(2, id)
                .executeUpdate();
        return new Numero(((Number) fila[1]).longValue(), numero);
    }
}
