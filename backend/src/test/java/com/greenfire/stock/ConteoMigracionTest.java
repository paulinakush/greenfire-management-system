package com.greenfire.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.TipoArticulo;

/** Reglas de los conteos que garantiza la base, aunque se inserte por fuera de la aplicación. */
@PruebaIntegracion
class ConteoMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;

    Ambito centro;
    Ambito norte;
    Articulo coco;
    Articulo macetaNorte;
    long principal;
    long deposito;
    long usuario;

    @BeforeEach
    void datos() {
        Ambito fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", datos.categoria("Sustratos"));
        macetaNorte = datos.articulo(norte, TipoArticulo.reventa, "Maceta", datos.categoria("Macetas"));
        principal = datos.principal(centro).getId();
        deposito = datos.ubicacion(centro, "Depósito").getId();
        usuario = datos.usuario("Ana").getId();
    }

    @Test
    void hayUnSoloConteoAbiertoPorUbicacion() {
        long primero = conteo(principal);
        rechaza(() -> conteo(principal));
        conteo(deposito);

        cerrar(primero);
        conteo(principal);
    }

    @Test
    void unConteoCerradoNoSeModificaNiSeAnula() {
        long id = conteo(principal);
        item(id, coco.getId(), "5");
        cerrar(id);

        rechazaModificacion(() -> jdbc.update("update conteos_stock set observaciones = 'x' where id = ?", id));
        rechazaModificacion(() -> jdbc.update(
                "update conteos_stock set estado = 'anulado', cerrado_at = null, anulado_at = now() where id = ?", id));
        rechazaModificacion(() -> jdbc.update("update detalle_conteo set cantidad_contada = 1 where conteo_id = ?", id));
        rechazaModificacion(() -> jdbc.update("delete from detalle_conteo where conteo_id = ?", id));
        rechazaModificacion(() -> item(id, macetaNorte.getId(), "1"));
    }

    @Test
    void unConteoAnuladoNoVuelveAAbrirse() {
        long id = conteo(principal);
        jdbc.update("update conteos_stock set estado = 'anulado', anulado_at = now() where id = ?", id);

        rechazaModificacion(() -> jdbc.update(
                "update conteos_stock set estado = 'abierto', anulado_at = null where id = ?", id));
        rechazaModificacion(() -> item(id, coco.getId(), "1"));
    }

    @Test
    void ningunConteoSeBorra() {
        long id = conteo(principal);

        rechazaModificacion(() -> jdbc.update("delete from conteos_stock where id = ?", id));
        rechazaModificacion(() -> jdbc.execute("truncate conteos_stock cascade"));
        assertThat(jdbc.queryForObject("select estado from conteos_stock where id = ?", String.class, id))
                .isEqualTo("abierto");
    }

    @Test
    void elEstadoYSusFechasSonCoherentes() {
        long id = conteo(principal);
        rechaza(() -> jdbc.update("update conteos_stock set estado = 'cerrado' where id = ?", id));
        rechaza(() -> jdbc.update("update conteos_stock set cerrado_at = now() where id = ?", id));
        rechaza(() -> jdbc.update("update conteos_stock set estado = 'otro' where id = ?", id));
    }

    @Test
    void elDetalleSoloTieneArticulosQueElAmbitoVeContadosEnCantidadesValidas() {
        long id = conteo(principal);

        rechaza(() -> item(id, macetaNorte.getId(), "1"));
        rechaza(() -> item(id, coco.getId(), "1.5"));
        rechaza(() -> item(id, coco.getId(), "-1"));
        item(id, coco.getId(), "3");
        rechaza(() -> item(id, coco.getId(), "4"));
    }

    @Test
    void losMovimientosDeConteoApuntanAUnConteoDeSuUbicacion() {
        long id = conteo(principal);

        rechaza(() -> movimiento(principal, "conteo", null, null));
        rechaza(() -> movimiento(principal, "ajuste", "Carga", id));
        rechaza(() -> movimiento(deposito, "conteo", null, id));
        rechaza(() -> movimiento(principal, "conteo", null, 999_999_999L));
        movimiento(principal, "conteo", null, id);
    }

    private long conteo(long ubicacion) {
        return jdbc.queryForObject("""
                insert into conteos_stock (ubicacion_stock_id, usuario_id, fecha) values (?, ?, current_date)
                returning id
                """, Long.class, ubicacion, usuario);
    }

    private void item(long conteo, long articulo, String cantidad) {
        jdbc.update("insert into detalle_conteo (conteo_id, articulo_id, cantidad_contada) values (?, ?, ?::numeric)",
                conteo, articulo, cantidad);
    }

    private void cerrar(long conteo) {
        jdbc.update("update conteos_stock set estado = 'cerrado', cerrado_at = now() where id = ?", conteo);
    }

    private void movimiento(long ubicacion, String tipo, String motivo, Long conteo) {
        jdbc.update("""
                insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, motivo,
                                               conteo_id)
                values (?, ?, ?, 1, ?, ?, ?)
                """, coco.getId(), ubicacion, tipo, usuario, motivo, conteo);
    }

    /** Cada intento corre en un savepoint, así un rechazo no aborta la transacción del test. */
    private void enSavepoint(Runnable operacion) {
        jdbc.execute("savepoint intento");
        try {
            operacion.run();
        } finally {
            jdbc.execute("rollback to savepoint intento");
        }
    }

    private void rechaza(Runnable operacion) {
        assertThatThrownBy(() -> enSavepoint(operacion)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void rechazaModificacion(Runnable operacion) {
        assertThatThrownBy(() -> enSavepoint(operacion)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageMatching("(?s).*(no se puede modificar|no se borran).*");
    }
}
