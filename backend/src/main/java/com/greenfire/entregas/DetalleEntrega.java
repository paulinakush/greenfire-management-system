package com.greenfire.entregas;

import java.math.BigDecimal;

import org.hibernate.annotations.DynamicUpdate;

import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Un renglón de una entrega, con el precio de la lista al enviar. Solo se le carga la cantidad recibida. */
@Entity
@DynamicUpdate
@Table(name = "detalle_entregas")
public class DetalleEntrega extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Entrega entrega;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Articulo articulo;
    private Long loteId;
    private BigDecimal cantidadEnviada;
    private BigDecimal cantidadRecibida;
    private BigDecimal precioUnitario;
    private BigDecimal subtotal;

    protected DetalleEntrega() {
    }

    DetalleEntrega(Entrega entrega, Articulo articulo, Long loteId, BigDecimal cantidadEnviada,
            BigDecimal precioUnitario, BigDecimal subtotal) {
        this.entrega = entrega;
        this.articulo = articulo;
        this.loteId = loteId;
        this.cantidadEnviada = cantidadEnviada;
        this.precioUnitario = precioUnitario;
        this.subtotal = subtotal;
    }

    void setCantidadRecibida(BigDecimal cantidadRecibida) {
        this.cantidadRecibida = cantidadRecibida;
    }

    /** Lo que no llegó; cero mientras no se recibió. */
    public BigDecimal faltante() {
        return cantidadRecibida == null ? BigDecimal.ZERO : cantidadEnviada.subtract(cantidadRecibida);
    }

    public Entrega getEntrega() {
        return entrega;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public Long getLoteId() {
        return loteId;
    }

    public BigDecimal getCantidadEnviada() {
        return cantidadEnviada;
    }

    public BigDecimal getCantidadRecibida() {
        return cantidadRecibida;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }
}
