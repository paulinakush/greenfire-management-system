package com.greenfire;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Para los tests que corren sin la transacción del test (NOT_SUPPORTED) y confirman datos de verdad: anota el
 * último id de cada tabla al empezar y al terminar borra todo lo posterior. Borra con los triggers desactivados
 * ({@code session_replication_role = replica}), porque los movimientos de stock, la auditoría y los conteos
 * cerrados no se pueden borrar de otra forma.
 */
public class DatosConfirmados {

    private static final List<String> TABLAS = List.of("movimientos_stock", "detalle_conteo", "conteos_stock",
            "stock_articulos", "ubicaciones_stock", "articulos", "categorias", "auditorias", "usuario_ambitos",
            "usuarios", "ambitos", "entidades_fiscales");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaccion;
    private final Map<String, Long> ultimoId = new HashMap<>();

    public DatosConfirmados(JdbcTemplate jdbc, TransactionTemplate transaccion) {
        this.jdbc = jdbc;
        this.transaccion = transaccion;
        for (String tabla : TABLAS) {
            ultimoId.put(tabla, jdbc.queryForObject("select coalesce(max(id), 0) from " + tabla, Long.class));
        }
    }

    /** El último id que tenía la tabla al empezar el test. */
    public long ultimoId(String tabla) {
        return ultimoId.get(tabla);
    }

    public void limpiar() {
        transaccion.executeWithoutResult(estado -> {
            jdbc.execute("set local session_replication_role = replica");
            String nuevos = "select id::text from usuarios where id > ?";
            jdbc.update("""
                    delete from spring_session_attributes where session_primary_id in
                        (select primary_id from spring_session where principal_name in (%s))
                    """.formatted(nuevos), ultimoId.get("usuarios"));
            jdbc.update("delete from spring_session where principal_name in (%s)".formatted(nuevos),
                    ultimoId.get("usuarios"));
            for (String tabla : TABLAS) {
                jdbc.update("delete from " + tabla + " where id > ?", ultimoId.get(tabla));
            }
        });
    }
}
