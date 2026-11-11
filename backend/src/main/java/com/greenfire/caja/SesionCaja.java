package com.greenfire.caja;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;

import org.hibernate.annotations.DynamicUpdate;

import com.greenfire.ambitos.Ambito;
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
 * Sesión de caja de un local: funciona como turno, compartida por quienes atienden. Nace abierta, se cierra una
 * vez con los totales de sus movimientos y después solo cambia por la corrección autorizada del dueño (V13).
 */
@Entity
@DynamicUpdate
@Table(name = "sesiones_caja")
public class SesionCaja extends EntidadBase {

    /** Una sesión abierta hace más que esto aparece como olvidada para el dueño. No se cierra sola. */
    public static final Duration OLVIDADA = Duration.ofHours(24);

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(updatable = false)
    private Ambito ambito;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_apertura_id", updatable = false)
    private Usuario usuarioApertura;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_cierre_id")
    private Usuario usuarioCierre;
    private OffsetDateTime abiertaAt;
    private OffsetDateTime cerradaAt;
    private BigDecimal saldoInicial;
    private BigDecimal totalEfectivo;
    private BigDecimal totalTransferencia;
    private BigDecimal totalBilletera;
    private BigDecimal totalGeneral;
    private BigDecimal ingresosEfectivo;
    private BigDecimal egresosEfectivo;
    private BigDecimal efectivoEsperado;
    private BigDecimal efectivoContado;
    private BigDecimal diferencia;
    @Enumerated(EnumType.STRING)
    private EstadoCaja estado = EstadoCaja.abierta;
    private String observaciones;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corregida_por_usuario_id")
    private Usuario corregidaPor;
    private OffsetDateTime corregidaAt;
    private String motivoCorreccion;

    protected SesionCaja() {
    }

    SesionCaja(Ambito ambito, Usuario usuarioApertura, BigDecimal saldoInicial) {
        this.ambito = ambito;
        this.usuarioApertura = usuarioApertura;
        this.saldoInicial = saldoInicial;
        this.abiertaAt = OffsetDateTime.now();
    }

    void cerrar(Usuario usuario, CierreCaja.Totales t, BigDecimal contado, String observaciones) {
        this.estado = EstadoCaja.cerrada;
        this.usuarioCierre = usuario;
        this.cerradaAt = OffsetDateTime.now();
        this.totalEfectivo = t.totalEfectivo();
        this.totalTransferencia = t.totalTransferencia();
        this.totalBilletera = t.totalBilletera();
        this.totalGeneral = t.totalGeneral();
        this.ingresosEfectivo = t.ingresosEfectivo();
        this.egresosEfectivo = t.egresosEfectivo();
        this.efectivoEsperado = t.efectivoEsperado();
        this.efectivoContado = contado;
        this.diferencia = contado.subtract(t.efectivoEsperado());
        this.observaciones = observaciones;
    }

    void corregir(Usuario usuario, BigDecimal contado, String motivo) {
        this.efectivoContado = contado;
        this.diferencia = contado.subtract(efectivoEsperado);
        this.corregidaPor = usuario;
        this.corregidaAt = OffsetDateTime.now();
        this.motivoCorreccion = motivo;
    }

    public boolean estaAbierta() {
        return estado == EstadoCaja.abierta;
    }

    /** Abierta hace más de 24 horas: alguien se olvidó de cerrarla. */
    public boolean olvidada(OffsetDateTime ahora) {
        return estaAbierta() && abiertaAt.plus(OLVIDADA).isBefore(ahora);
    }

    public Ambito getAmbito() {
        return ambito;
    }

    public Usuario getUsuarioApertura() {
        return usuarioApertura;
    }

    public Usuario getUsuarioCierre() {
        return usuarioCierre;
    }

    public OffsetDateTime getAbiertaAt() {
        return abiertaAt;
    }

    public OffsetDateTime getCerradaAt() {
        return cerradaAt;
    }

    public BigDecimal getSaldoInicial() {
        return saldoInicial;
    }

    public BigDecimal getTotalEfectivo() {
        return totalEfectivo;
    }

    public BigDecimal getTotalTransferencia() {
        return totalTransferencia;
    }

    public BigDecimal getTotalBilletera() {
        return totalBilletera;
    }

    public BigDecimal getTotalGeneral() {
        return totalGeneral;
    }

    public BigDecimal getIngresosEfectivo() {
        return ingresosEfectivo;
    }

    public BigDecimal getEgresosEfectivo() {
        return egresosEfectivo;
    }

    public BigDecimal getEfectivoEsperado() {
        return efectivoEsperado;
    }

    public BigDecimal getEfectivoContado() {
        return efectivoContado;
    }

    public BigDecimal getDiferencia() {
        return diferencia;
    }

    public EstadoCaja getEstado() {
        return estado;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public Usuario getCorregidaPor() {
        return corregidaPor;
    }

    public OffsetDateTime getCorregidaAt() {
        return corregidaAt;
    }

    public String getMotivoCorreccion() {
        return motivoCorreccion;
    }
}
