package com.greenfire.fiscal.dto;

import java.util.List;

/** Listas de precios que quedaron como estaban al cambiar la condición frente al IVA de su entidad. */
public record AdvertenciaCondicionIva(String mensaje, List<ListaPreciosAfectada> listasAfectadas) {

    public static final String MENSAJE = "Cambió la condición frente al IVA. Los precios de estas listas no se "
            + "recalcularon: revisalos. El margen y el precio público se calculan desde ahora con la nueva condición.";
}
