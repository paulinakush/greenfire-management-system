package com.greenfire.ventas;

import java.math.BigDecimal;

import org.hibernate.annotations.Immutable;

import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Lo devuelto de un renglón de la venta. Solo se inserta (V14). */
@Entity
@Immutable
@Table(name = "detalle_devoluciones_venta")
public class DetalleDevolucionVenta extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "devolucion_id")
    private DevolucionVenta devolucion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "detalle_venta_id")
    private DetalleVenta detalleVenta;
    private BigDecimal cantidad;
    private BigDecimal subtotal;

    protected DetalleDevolucionVenta() {
    }

    DetalleDevolucionVenta(DevolucionVenta devolucion, DetalleVenta detalleVenta, BigDecimal cantidad,
            BigDecimal subtotal) {
        this.devolucion = devolucion;
        this.detalleVenta = detalleVenta;
        this.cantidad = cantidad;
        this.subtotal = subtotal;
    }

    public DevolucionVenta getDevolucion() {
        return devolucion;
    }

    public DetalleVenta getDetalleVenta() {
        return detalleVenta;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }
}
