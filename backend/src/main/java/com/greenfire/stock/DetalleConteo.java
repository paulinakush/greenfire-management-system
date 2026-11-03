package com.greenfire.stock;

import java.math.BigDecimal;

import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Cantidad contada de un artículo en un conteo. La del sistema se toma al cerrar; mientras tanto es null. */
@Entity
@Table(name = "detalle_conteo")
public class DetalleConteo extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conteo_id")
    private ConteoStock conteo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Articulo articulo;
    private BigDecimal cantidadContada;
    private BigDecimal cantidadSistema;

    protected DetalleConteo() {
    }

    DetalleConteo(ConteoStock conteo, Articulo articulo) {
        this.conteo = conteo;
        this.articulo = articulo;
    }

    /** Contada menos sistema; null mientras el conteo está abierto. */
    public BigDecimal diferencia() {
        return cantidadSistema == null ? null : cantidadContada.subtract(cantidadSistema);
    }

    public ConteoStock getConteo() {
        return conteo;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public BigDecimal getCantidadContada() {
        return cantidadContada;
    }

    void setCantidadContada(BigDecimal cantidadContada) {
        this.cantidadContada = cantidadContada;
    }

    public BigDecimal getCantidadSistema() {
        return cantidadSistema;
    }

    void setCantidadSistema(BigDecimal cantidadSistema) {
        this.cantidadSistema = cantidadSistema;
    }
}
