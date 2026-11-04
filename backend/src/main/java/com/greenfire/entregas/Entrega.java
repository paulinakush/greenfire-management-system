package com.greenfire.entregas;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.DynamicUpdate;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.EntidadBase;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.stock.UbicacionStock;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Entrega de la fábrica a un local. Es una venta: lleva precio, y en la etapa 3 comprobante y cuenta corriente.
 * Los datos del envío no cambian; la base solo admite las transiciones de {@link EstadoEntrega} y una resolución
 * de la diferencia (V9).
 */
@Entity
@DynamicUpdate
@Table(name = "entregas")
public class Entrega extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ambito_origen_id")
    private Ambito origen;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ambito_destino_id")
    private Ambito destino;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ubicacion_origen_id")
    private UbicacionStock ubicacionOrigen;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ubicacion_destino_id")
    private UbicacionStock ubicacionDestino;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lista_precios_id")
    private ListaPrecios lista;
    @Enumerated(EnumType.STRING)
    private EstadoEntrega estado = EstadoEntrega.enviada;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_envio_id")
    private Usuario usuarioEnvio;
    private OffsetDateTime fechaEnvio;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_recepcion_id")
    private Usuario usuarioRecepcion;
    private OffsetDateTime fechaRecepcion;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_anulacion_id")
    private Usuario usuarioAnulacion;
    private OffsetDateTime anuladaAt;
    @Enumerated(EnumType.STRING)
    private ResolucionDiferencia resolucionDiferencia;
    private String motivoResolucion;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_resolucion_id")
    private Usuario usuarioResolucion;
    private OffsetDateTime diferenciaResueltaAt;
    private BigDecimal total;
    private String observaciones;
    private String observacionesRecepcion;

    protected Entrega() {
    }

    Entrega(Ambito origen, Ambito destino, UbicacionStock ubicacionOrigen, ListaPrecios lista, Usuario usuarioEnvio,
            BigDecimal total, String observaciones) {
        this.origen = origen;
        this.destino = destino;
        this.ubicacionOrigen = ubicacionOrigen;
        this.lista = lista;
        this.usuarioEnvio = usuarioEnvio;
        this.fechaEnvio = OffsetDateTime.now();
        this.total = total;
        this.observaciones = observaciones;
    }

    void recibir(UbicacionStock ubicacion, Usuario usuario, boolean conFaltante, String observaciones) {
        estado = conFaltante ? EstadoEntrega.diferencia_informada : EstadoEntrega.recibida;
        ubicacionDestino = ubicacion;
        usuarioRecepcion = usuario;
        fechaRecepcion = OffsetDateTime.now();
        observacionesRecepcion = observaciones;
    }

    void anular(Usuario usuario) {
        estado = EstadoEntrega.anulada;
        usuarioAnulacion = usuario;
        anuladaAt = OffsetDateTime.now();
    }

    void resolverDiferencia(ResolucionDiferencia forma, String motivo, Usuario usuario) {
        resolucionDiferencia = forma;
        motivoResolucion = motivo;
        usuarioResolucion = usuario;
        diferenciaResueltaAt = OffsetDateTime.now();
    }

    public boolean enviada() {
        return estado == EstadoEntrega.enviada;
    }

    public boolean diferenciaPendiente() {
        return estado == EstadoEntrega.diferencia_informada && diferenciaResueltaAt == null;
    }

    public Ambito getOrigen() {
        return origen;
    }

    public Ambito getDestino() {
        return destino;
    }

    public UbicacionStock getUbicacionOrigen() {
        return ubicacionOrigen;
    }

    public UbicacionStock getUbicacionDestino() {
        return ubicacionDestino;
    }

    public ListaPrecios getLista() {
        return lista;
    }

    public EstadoEntrega getEstado() {
        return estado;
    }

    public Usuario getUsuarioEnvio() {
        return usuarioEnvio;
    }

    public OffsetDateTime getFechaEnvio() {
        return fechaEnvio;
    }

    public Usuario getUsuarioRecepcion() {
        return usuarioRecepcion;
    }

    public OffsetDateTime getFechaRecepcion() {
        return fechaRecepcion;
    }

    public Usuario getUsuarioAnulacion() {
        return usuarioAnulacion;
    }

    public OffsetDateTime getAnuladaAt() {
        return anuladaAt;
    }

    public ResolucionDiferencia getResolucionDiferencia() {
        return resolucionDiferencia;
    }

    public String getMotivoResolucion() {
        return motivoResolucion;
    }

    public Usuario getUsuarioResolucion() {
        return usuarioResolucion;
    }

    public OffsetDateTime getDiferenciaResueltaAt() {
        return diferenciaResueltaAt;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public String getObservacionesRecepcion() {
        return observacionesRecepcion;
    }
}
