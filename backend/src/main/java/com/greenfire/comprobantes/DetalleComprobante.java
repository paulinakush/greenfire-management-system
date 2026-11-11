package com.greenfire.comprobantes;

import java.math.BigDecimal;

import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Renglón de un comprobante. Se carga al emitir, en la misma transacción; después no cambia ni se borra (V12). El
 * precio unitario es con o sin IVA según {@link Comprobante#isPreciosConIva()}.
 */
@Entity
@Table(name = "detalle_comprobante")
public class DetalleComprobante extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(updatable = false)
    private Comprobante comprobante;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(updatable = false)
    private Articulo articulo;
    private String descripcion;
    private BigDecimal cantidad;
    private BigDecimal precioUnitario;
    private BigDecimal alicuotaIva;
    private BigDecimal subtotal;

    protected DetalleComprobante() {
    }

    DetalleComprobante(Comprobante comprobante, Articulo articulo, String descripcion, BigDecimal cantidad,
            BigDecimal precioUnitario, BigDecimal alicuotaIva, BigDecimal subtotal) {
        this.comprobante = comprobante;
        this.articulo = articulo;
        this.descripcion = descripcion;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
        this.alicuotaIva = alicuotaIva;
        this.subtotal = subtotal;
    }

    public Comprobante getComprobante() {
        return comprobante;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public BigDecimal getAlicuotaIva() {
        return alicuotaIva;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }
}
