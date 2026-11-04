package com.greenfire.fiscal.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * La entidad editada, con los mismos campos que {@link EntidadFiscalResponse}. {@code advertencia} es null salvo
 * que haya cambiado la condición frente al IVA y la entidad tenga listas de precios.
 */
public record EntidadFiscalEdicionResponse(@JsonUnwrapped EntidadFiscalResponse entidad,
        AdvertenciaCondicionIva advertencia) {
}
