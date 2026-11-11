package com.greenfire.clientes;

import java.math.BigDecimal;

import org.hibernate.annotations.DynamicUpdate;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.EntidadBase;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.precios.ListaPrecios;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Cliente de un ámbito: cada local tiene los suyos; la fábrica, un cliente por local (lo crea la base, con los
 * datos del local y de su entidad fiscal) y sus mayoristas. No se borra: se desactiva.
 */
@Entity
@DynamicUpdate
@Table(name = "clientes")
public class Cliente extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(updatable = false)
    private Ambito ambito;
    private String nombre;
    private String razonSocial;
    @Enumerated(EnumType.STRING)
    private TipoDocumento documentoTipo;
    private String documentoNumero;
    @Enumerated(EnumType.STRING)
    private CondicionIva condicionIva = CondicionIva.consumidor_final;
    private String telefono;
    private String email;
    private String direccion;
    private boolean cuentaCorrienteHabilitada;
    private BigDecimal limiteCredito;
    @ManyToOne(fetch = FetchType.LAZY)
    private ListaPrecios listaPrecios;
    private Integer plazoPagoDias;
    /** El local que representa, si es un cliente de la fábrica creado por la base. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(insertable = false, updatable = false)
    private Ambito ambitoRepresentado;
    private boolean activo = true;

    public boolean representaUnLocal() {
        return ambitoRepresentado != null;
    }

    /** Nombre para mostrar: el nombre o, si no tiene, la razón social. */
    public String nombreVisible() {
        return nombre != null && !nombre.isBlank() ? nombre : razonSocial;
    }

    public Ambito getAmbito() {
        return ambito;
    }

    public void setAmbito(Ambito ambito) {
        this.ambito = ambito;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getRazonSocial() {
        return razonSocial;
    }

    public void setRazonSocial(String razonSocial) {
        this.razonSocial = razonSocial;
    }

    public TipoDocumento getDocumentoTipo() {
        return documentoTipo;
    }

    public void setDocumentoTipo(TipoDocumento documentoTipo) {
        this.documentoTipo = documentoTipo;
    }

    public String getDocumentoNumero() {
        return documentoNumero;
    }

    public void setDocumentoNumero(String documentoNumero) {
        this.documentoNumero = documentoNumero;
    }

    public CondicionIva getCondicionIva() {
        return condicionIva;
    }

    public void setCondicionIva(CondicionIva condicionIva) {
        this.condicionIva = condicionIva;
    }

    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public boolean isCuentaCorrienteHabilitada() {
        return cuentaCorrienteHabilitada;
    }

    public void setCuentaCorrienteHabilitada(boolean cuentaCorrienteHabilitada) {
        this.cuentaCorrienteHabilitada = cuentaCorrienteHabilitada;
    }

    public BigDecimal getLimiteCredito() {
        return limiteCredito;
    }

    public void setLimiteCredito(BigDecimal limiteCredito) {
        this.limiteCredito = limiteCredito;
    }

    public ListaPrecios getListaPrecios() {
        return listaPrecios;
    }

    public void setListaPrecios(ListaPrecios listaPrecios) {
        this.listaPrecios = listaPrecios;
    }

    public Integer getPlazoPagoDias() {
        return plazoPagoDias;
    }

    public void setPlazoPagoDias(Integer plazoPagoDias) {
        this.plazoPagoDias = plazoPagoDias;
    }

    public Ambito getAmbitoRepresentado() {
        return ambitoRepresentado;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }
}
