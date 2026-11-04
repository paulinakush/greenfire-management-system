package com.greenfire.catalogo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;

/** Reglas del catálogo que garantiza la base, aunque se inserte por fuera de la aplicación. */
@PruebaIntegracion
class CatalogoMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;

    Ambito fabrica;
    Ambito local;
    long categoria;

    @BeforeEach
    void ambitos() {
        fabrica = datos.fabrica();
        local = datos.local("Centro");
        categoria = datos.categoria("Sustratos").getId();
    }

    @Test
    void laReventaNoPuedeSerDeLaFabricaNiUnProductoTerminadoDeUnLocal() {
        rechaza(() -> articulo(fabrica.getId(), "reventa", categoria));
        rechaza(() -> articulo(local.getId(), "producto_terminado", categoria));
    }

    @Test
    void losInsumosNoLlevanCategoriaYLosDemasSi() {
        rechaza(() -> articulo(fabrica.getId(), "insumo", categoria));
        rechaza(() -> articulo(fabrica.getId(), "producto_terminado", null));
    }

    @Test
    void laAlicuotaYLaUnidadTienenValoresFijos() {
        rechaza(() -> jdbc.update("""
                insert into articulos (ambito_id, tipo, nombre, unidad_medida, alicuota_iva)
                values (?, 'insumo', 'X', 'kg', 15)
                """, fabrica.getId()));
        rechaza(() -> jdbc.update("""
                insert into articulos (ambito_id, tipo, nombre, unidad_medida, alicuota_iva)
                values (?, 'insumo', 'X', 'tonelada', 21)
                """, fabrica.getId()));
    }

    @Test
    void unLocalNoTieneCostoDeLosInsumosDeLaFabricaYElCostoNoEsNegativo() {
        long insumo = articulo(fabrica.getId(), "insumo", null);
        long producto = articulo(fabrica.getId(), "producto_terminado", categoria);

        rechaza(() -> costo(insumo, local.getId(), "10"));
        rechaza(() -> costo(producto, fabrica.getId(), "-1"));
        costo(producto, local.getId(), "10");
    }

    @Test
    void unLocalNoTieneCostoDeLaReventaDeOtroLocal() {
        Ambito otro = datos.local("Norte");
        long reventa = articulo(otro.getId(), "reventa", categoria);

        rechaza(() -> costo(reventa, local.getId(), "10"));
    }

    /** Cada intento corre en un savepoint, así un rechazo no aborta la transacción del test. */
    private void rechaza(Runnable insert) {
        jdbc.execute("savepoint intento");
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("rollback to savepoint intento");
    }

    private long articulo(long ambito, String tipo, Long categoriaId) {
        return jdbc.queryForObject("""
                insert into articulos (ambito_id, tipo, nombre, categoria_id, unidad_medida, alicuota_iva)
                values (?, ?, ?, ?, 'unidad', 21) returning id
                """, Long.class, ambito, tipo, tipo + System.nanoTime(), categoriaId);
    }

    private void costo(long articulo, long ambito, String costo) {
        jdbc.update("insert into costos_articulo (articulo_id, ambito_id, costo_actual) values (?, ?, ?::numeric)",
                articulo, ambito, costo);
    }
}
