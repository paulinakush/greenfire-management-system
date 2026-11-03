package com.greenfire.catalogo;

import java.math.BigDecimal;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Insumo, producto terminado o reventa. El ámbito es su dueño: la fábrica para insumos y productos terminados,
 * un local para la reventa. El costo no está acá: cada ámbito tiene el suyo en {@link CostoArticulo}.
 */
@Entity
@Table(name = "articulos")
public class Articulo extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ambito ambito;
    @Enumerated(EnumType.STRING)
    private TipoArticulo tipo;
    private String nombre;
    private String descripcion;
    @ManyToOne(fetch = FetchType.LAZY)
    private Categoria categoria;
    private String marca;
    @Enumerated(EnumType.STRING)
    private UnidadMedida unidadMedida;
    private String imagen;
    private BigDecimal alicuotaIva;
    private boolean visibleWeb;
    private boolean activo = true;

    /** Lo que ve un ámbito: sus propios artículos y, si es un local, los productos terminados de la fábrica. */
    public boolean visiblePara(Ambito ambito) {
        return esDe(ambito) || (ambito.getTipo() == TipoAmbito.local && tipo == TipoArticulo.producto_terminado);
    }

    public boolean esDe(Ambito ambito) {
        return this.ambito.getId().equals(ambito.getId());
    }

    public Ambito getAmbito() {
        return ambito;
    }

    public void setAmbito(Ambito ambito) {
        this.ambito = ambito;
    }

    public TipoArticulo getTipo() {
        return tipo;
    }

    public void setTipo(TipoArticulo tipo) {
        this.tipo = tipo;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public void setCategoria(Categoria categoria) {
        this.categoria = categoria;
    }

    public String getMarca() {
        return marca;
    }

    public void setMarca(String marca) {
        this.marca = marca;
    }

    public UnidadMedida getUnidadMedida() {
        return unidadMedida;
    }

    public void setUnidadMedida(UnidadMedida unidadMedida) {
        this.unidadMedida = unidadMedida;
    }

    public String getImagen() {
        return imagen;
    }

    public void setImagen(String imagen) {
        this.imagen = imagen;
    }

    public BigDecimal getAlicuotaIva() {
        return alicuotaIva;
    }

    public void setAlicuotaIva(BigDecimal alicuotaIva) {
        this.alicuotaIva = alicuotaIva;
    }

    public boolean isVisibleWeb() {
        return visibleWeb;
    }

    public void setVisibleWeb(boolean visibleWeb) {
        this.visibleWeb = visibleWeb;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }
}
