package com.greenfire.catalogo;

import java.math.BigDecimal;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Costo de un artículo en un ámbito. Cada ámbito ve solo el suyo. */
@Entity
@Table(name = "costos_articulo")
public class CostoArticulo extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Articulo articulo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ambito ambito;
    private BigDecimal costoActual;

    protected CostoArticulo() {
    }

    public CostoArticulo(Articulo articulo, Ambito ambito) {
        this.articulo = articulo;
        this.ambito = ambito;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public Ambito getAmbito() {
        return ambito;
    }

    public BigDecimal getCostoActual() {
        return costoActual;
    }

    public void setCostoActual(BigDecimal costoActual) {
        this.costoActual = costoActual;
    }
}
