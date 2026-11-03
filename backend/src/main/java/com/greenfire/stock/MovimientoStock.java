package com.greenfire.stock;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.Immutable;

import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.EntidadBase;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Entrada (cantidad positiva) o salida (negativa) de un artículo en una ubicación. Solo se inserta, desde
 * {@link RegistroStock}: la base rechaza UPDATE, DELETE y TRUNCATE (V6), y un error se corrige con otro movimiento.
 */
@Entity
@Immutable
@Table(name = "movimientos_stock")
public class MovimientoStock extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Articulo articulo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ubicacion_stock_id")
    private UbicacionStock ubicacion;
    @Enumerated(EnumType.STRING)
    private TipoMovimientoStock tipo;
    private BigDecimal cantidad;
    private Long loteId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Usuario usuario;
    private OffsetDateTime fechaHora;
    private String motivo;
    private String observaciones;

    protected MovimientoStock() {
    }

    MovimientoStock(Articulo articulo, UbicacionStock ubicacion, TipoMovimientoStock tipo, BigDecimal cantidad,
            Long loteId, Usuario usuario, String motivo, String observaciones) {
        this.articulo = articulo;
        this.ubicacion = ubicacion;
        this.tipo = tipo;
        this.cantidad = cantidad;
        this.loteId = loteId;
        this.usuario = usuario;
        this.fechaHora = OffsetDateTime.now();
        this.motivo = motivo;
        this.observaciones = observaciones;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public UbicacionStock getUbicacion() {
        return ubicacion;
    }

    public TipoMovimientoStock getTipo() {
        return tipo;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public Long getLoteId() {
        return loteId;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public OffsetDateTime getFechaHora() {
        return fechaHora;
    }

    public String getMotivo() {
        return motivo;
    }

    public String getObservaciones() {
        return observaciones;
    }
}
