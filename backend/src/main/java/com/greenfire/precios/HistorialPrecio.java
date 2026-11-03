package com.greenfire.precios;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.Immutable;

import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.EntidadBase;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Cambio de precio de un artículo en una lista. Solo se inserta, igual que la auditoría: la base rechaza UPDATE,
 * DELETE y TRUNCATE (migración V5). El precio anterior es null al agregar el artículo y el nuevo, al sacarlo.
 */
@Entity
@Immutable
@Table(name = "historial_precios")
public class HistorialPrecio extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lista_id")
    private ListaPrecios lista;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Articulo articulo;
    private BigDecimal precioAnterior;
    private BigDecimal precioNuevo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Usuario usuario;
    private OffsetDateTime fechaHora;

    protected HistorialPrecio() {
    }

    HistorialPrecio(ListaPrecios lista, Articulo articulo, BigDecimal precioAnterior, BigDecimal precioNuevo,
            Usuario usuario) {
        this.lista = lista;
        this.articulo = articulo;
        this.precioAnterior = precioAnterior;
        this.precioNuevo = precioNuevo;
        this.usuario = usuario;
        this.fechaHora = OffsetDateTime.now();
    }

    public ListaPrecios getLista() {
        return lista;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public BigDecimal getPrecioAnterior() {
        return precioAnterior;
    }

    public BigDecimal getPrecioNuevo() {
        return precioNuevo;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public OffsetDateTime getFechaHora() {
        return fechaHora;
    }
}
