package com.greenfire.stock;

import java.math.BigDecimal;

import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Stock de un artículo en una ubicación, con su mínimo y su máximo. La cantidad es un caché de la suma de los
 * movimientos que mantiene la base (trigger de V6): la aplicación nunca la escribe y, después de registrar
 * movimientos, la vuelve a leer con {@link StockArticuloRepository#cantidad}.
 */
@Entity
@Table(name = "stock_articulos")
public class StockArticulo extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Articulo articulo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ubicacion_stock_id")
    private UbicacionStock ubicacion;
    private BigDecimal stockMinimo;
    private BigDecimal stockMaximo;
    @Column(insertable = false, updatable = false)
    private BigDecimal cantidadActual;

    protected StockArticulo() {
    }

    public StockArticulo(Articulo articulo, UbicacionStock ubicacion) {
        this.articulo = articulo;
        this.ubicacion = ubicacion;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public UbicacionStock getUbicacion() {
        return ubicacion;
    }

    public BigDecimal getStockMinimo() {
        return stockMinimo;
    }

    public void setStockMinimo(BigDecimal stockMinimo) {
        this.stockMinimo = stockMinimo;
    }

    public BigDecimal getStockMaximo() {
        return stockMaximo;
    }

    public void setStockMaximo(BigDecimal stockMaximo) {
        this.stockMaximo = stockMaximo;
    }

    public BigDecimal getCantidadActual() {
        return cantidadActual;
    }
}
