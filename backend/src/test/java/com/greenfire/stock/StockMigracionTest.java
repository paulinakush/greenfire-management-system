package com.greenfire.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

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

/** Reglas del stock que garantiza la base, aunque se inserte por fuera de la aplicación. */
@PruebaIntegracion
class StockMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Articulo turba;
    Articulo coco;
    Articulo macetaNorte;
    long principalFabrica;
    long principalCentro;
    long usuario;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        turba = datos.articulo(fabrica, TipoArticulo.insumo, "Turba", null);
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", datos.categoria("Sustratos"));
        macetaNorte = datos.articulo(norte, TipoArticulo.reventa, "Maceta", datos.categoria("Macetas"));
        principalFabrica = datos.principal(fabrica).getId();
        principalCentro = datos.principal(centro).getId();
        usuario = datos.usuario("Ana").getId();
    }

    // --- Solo inserción ---

    @Test
    void losMovimientosNoSeModificanNiSeBorran() {
        long id = movimiento(coco.getId(), principalFabrica, "ajuste", "10", "Carga");

        rechazaModificacion(() -> jdbc.update("update movimientos_stock set cantidad = 1 where id = ?", id));
        rechazaModificacion(() -> jdbc.update("delete from movimientos_stock where id = ?", id));
        rechazaModificacion(() -> jdbc.execute("truncate movimientos_stock cascade"));
        assertThat(jdbc.queryForObject("select cantidad from movimientos_stock where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("10");
    }

    // --- Caché de cantidad ---

    @Test
    void cadaMovimientoSumaSuCantidadAlStockDeSuArticuloYUbicacion() {
        movimiento(coco.getId(), principalFabrica, "ajuste", "10", "Carga");
        movimiento(coco.getId(), principalFabrica, "ajuste", "-3", "Rotura");
        movimiento(coco.getId(), principalCentro, "ajuste", "4", "Carga");

        assertThat(cantidad(coco.getId(), principalFabrica)).isEqualByComparingTo("7");
        assertThat(cantidad(coco.getId(), principalCentro)).isEqualByComparingTo("4");
    }

    @Test
    void laCantidadNoSeEditaAMano() {
        movimiento(coco.getId(), principalFabrica, "ajuste", "10", "Carga");

        rechazaModificacion(() -> jdbc.update(
                "update stock_articulos set cantidad_actual = 99 where articulo_id = ? and ubicacion_stock_id = ?",
                coco.getId(), principalFabrica));
        rechazaModificacion(() -> jdbc.update(
                "insert into stock_articulos (articulo_id, ubicacion_stock_id, cantidad_actual) values (?, ?, 5)",
                turba.getId(), principalFabrica));
        // el mínimo y el máximo sí se editan
        jdbc.update("update stock_articulos set stock_minimo = 2, stock_maximo = 20 where articulo_id = ?",
                coco.getId());
        assertThat(cantidad(coco.getId(), principalFabrica)).isEqualByComparingTo("10");
    }

    @Test
    void elStockNoPuedeQuedarNegativo() {
        movimiento(coco.getId(), principalFabrica, "ajuste", "2", "Carga");

        assertThatThrownBy(() -> enSavepoint(() -> movimiento(coco.getId(), principalFabrica, "ajuste", "-3", "X")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("stock_articulos_sin_negativo");
        // ni siquiera la primera vez, sin fila previa
        assertThatThrownBy(() -> enSavepoint(() -> movimiento(turba.getId(), principalFabrica, "ajuste", "-1", "X")))
                .hasMessageContaining("stock_articulos_sin_negativo");
        assertThat(cantidad(coco.getId(), principalFabrica)).isEqualByComparingTo("2");
    }

    // --- Validaciones de cada movimiento ---

    @Test
    void todoAjusteExigeMotivoYNingunMovimientoEsCero() {
        rechaza(() -> movimiento(coco.getId(), principalFabrica, "ajuste", "1", null));
        rechaza(() -> movimiento(coco.getId(), principalFabrica, "ajuste", "1", "   "));
        rechaza(() -> movimiento(coco.getId(), principalFabrica, "ajuste", "0", "Carga"));
        movimiento(coco.getId(), principalFabrica, "entrega_recepcion", "1", null); // los demás tipos no lo exigen
    }

    @Test
    void loQueSeMideEnUnidadesSeMueveEnCantidadesEnteras() {
        rechaza(() -> movimiento(coco.getId(), principalFabrica, "ajuste", "1.5", "Carga"));
        jdbc.update("update articulos set unidad_medida = 'kg' where id = ?", turba.getId());
        movimiento(turba.getId(), principalFabrica, "ajuste", "1.5", "Carga");
    }

    @Test
    void cadaAmbitoTieneStockSoloDeLosArticulosQueVe() {
        rechaza(() -> movimiento(turba.getId(), principalCentro, "ajuste", "1", "Carga"));
        rechaza(() -> movimiento(macetaNorte.getId(), principalCentro, "ajuste", "1", "Carga"));
        rechaza(() -> movimiento(macetaNorte.getId(), principalFabrica, "ajuste", "1", "Carga"));
        rechaza(() -> jdbc.update("insert into stock_articulos (articulo_id, ubicacion_stock_id) values (?, ?)",
                macetaNorte.getId(), principalCentro));
        movimiento(coco.getId(), principalCentro, "ajuste", "1", "Carga");
        movimiento(macetaNorte.getId(), datos.principal(norte).getId(), "ajuste", "1", "Carga");
    }

    // --- Ubicaciones ---

    @Test
    void cadaAmbitoNaceConLaUbicacionPrincipalYLosNombresSonUnicosEnElAmbito() {
        Ambito nuevo = datos.local("Sur");
        assertThat(jdbc.queryForList("select nombre from ubicaciones_stock where ambito_id = ?", String.class,
                nuevo.getId())).containsExactly("Principal");

        rechaza(() -> jdbc.update("insert into ubicaciones_stock (ambito_id, nombre) values (?, 'PRINCIPAL')",
                nuevo.getId()));
    }

    private long movimiento(long articulo, long ubicacion, String tipo, String cantidad, String motivo) {
        return jdbc.queryForObject("""
                insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, motivo)
                values (?, ?, ?, ?::numeric, ?, ?) returning id
                """, Long.class, articulo, ubicacion, tipo, cantidad, usuario, motivo);
    }

    private BigDecimal cantidad(long articulo, long ubicacion) {
        return jdbc.queryForObject(
                "select cantidad_actual from stock_articulos where articulo_id = ? and ubicacion_stock_id = ?",
                BigDecimal.class, articulo, ubicacion);
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

    private void rechaza(Runnable insert) {
        assertThatThrownBy(() -> enSavepoint(insert)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void rechazaModificacion(Runnable operacion) {
        assertThatThrownBy(() -> enSavepoint(operacion)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageMatching("(?s).*(no se pueden modificar ni borrar|no se edita).*");
    }
}
