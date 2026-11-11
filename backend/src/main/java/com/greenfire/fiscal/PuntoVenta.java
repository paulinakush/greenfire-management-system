package com.greenfire.fiscal;

import java.util.HashSet;
import java.util.Set;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "puntos_venta")
public class PuntoVenta extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private EntidadFiscal entidadFiscal;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ambito ambito;
    private Integer numero;
    private String descripcion;
    private boolean activo = true;

    @ElementCollection
    @CollectionTable(name = "punto_venta_comprobantes", joinColumns = @JoinColumn(name = "punto_venta_id"))
    private Set<ComprobantePermitido> comprobantes = new HashSet<>();

    public EntidadFiscal getEntidadFiscal() {
        return entidadFiscal;
    }

    public Ambito getAmbito() {
        return ambito;
    }

    /** La entidad fiscal del punto de venta es siempre la de su ámbito. */
    public void setAmbito(Ambito ambito) {
        this.ambito = ambito;
        this.entidadFiscal = ambito.getEntidadFiscal();
    }

    public Integer getNumero() {
        return numero;
    }

    public void setNumero(Integer numero) {
        this.numero = numero;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }

    public Set<ComprobantePermitido> getComprobantes() {
        return comprobantes;
    }

    /**
     * Quita los que ya no van y suma los nuevos, sin tocar los que quedan: así sus filas (con el último número
     * emitido) no se borran y se vuelven a crear. Si un tipo se quita y se vuelve a agregar, la base retoma su
     * numeración desde el último comprobante emitido (V12).
     */
    public void setComprobantes(Set<ComprobantePermitido> comprobantes) {
        this.comprobantes.retainAll(comprobantes);
        this.comprobantes.addAll(comprobantes);
    }
}
