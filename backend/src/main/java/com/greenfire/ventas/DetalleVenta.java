package com.greenfire.ventas;

import java.math.BigDecimal;

import org.hibernate.annotations.Immutable;

import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.EntidadBase;
import com.greenfire.stock.UbicacionStock;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Renglón de una venta, con los precios, la alícuota y el costo copiados al vender. Solo se inserta (V14). */
@Entity
@Immutable
@Table(name = "detalle_ventas")
public class DetalleVenta extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Venta venta;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Articulo articulo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ubicacion_stock_id")
    private UbicacionStock ubicacion;
    private BigDecimal cantidad;
    private BigDecimal precioLista;
    private BigDecimal precioUnitario;
    private BigDecimal alicuotaIva;
    private BigDecimal costoUnitario;
    private BigDecimal subtotal;

    protected DetalleVenta() {
    }

    DetalleVenta(Venta venta, Articulo articulo, UbicacionStock ubicacion, BigDecimal cantidad,
            CalculoVenta.Linea linea, BigDecimal alicuotaIva, BigDecimal costoUnitario) {
        this.venta = venta;
        this.articulo = articulo;
        this.ubicacion = ubicacion;
        this.cantidad = cantidad;
        this.precioLista = linea.precioLista();
        this.precioUnitario = linea.precioUnitario();
        this.alicuotaIva = alicuotaIva;
        this.costoUnitario = costoUnitario;
        this.subtotal = linea.subtotal();
    }

    public Venta getVenta() {
        return venta;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public UbicacionStock getUbicacion() {
        return ubicacion;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public BigDecimal getPrecioLista() {
        return precioLista;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public BigDecimal getAlicuotaIva() {
        return alicuotaIva;
    }

    /** Costo del ámbito al vender; null si no tenía. */
    public BigDecimal getCostoUnitario() {
        return costoUnitario;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }
}
