package com.greenfire.precios;

import java.math.BigDecimal;

import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Precio de un artículo en una lista, con o sin IVA según la lista. */
@Entity
@Table(name = "lista_precios_items")
public class ListaPreciosItem extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lista_id")
    private ListaPrecios lista;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Articulo articulo;
    private BigDecimal precio;

    protected ListaPreciosItem() {
    }

    public ListaPreciosItem(ListaPrecios lista, Articulo articulo) {
        this.lista = lista;
        this.articulo = articulo;
    }

    public ListaPrecios getLista() {
        return lista;
    }

    public Articulo getArticulo() {
        return articulo;
    }

    public BigDecimal getPrecio() {
        return precio;
    }

    public void setPrecio(BigDecimal precio) {
        this.precio = precio;
    }
}
