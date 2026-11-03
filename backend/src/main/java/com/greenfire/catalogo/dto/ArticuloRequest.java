package com.greenfire.catalogo.dto;

import java.math.BigDecimal;

import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Alta de un artículo. El tipo no se puede cambiar después. */
public record ArticuloRequest(
        @NotNull TipoArticulo tipo,
        @NotBlank @Size(max = 200) String nombre,
        @Size(max = 2000) String descripcion,
        Long categoriaId,
        @Size(max = 120) String marca,
        @NotNull UnidadMedida unidadMedida,
        @Size(max = 255) @Pattern(regexp = ArticuloDatos.RUTA_IMAGEN,
                message = "debe ser una ruta dentro de assets/productos/") String imagen,
        @NotNull BigDecimal alicuotaIva,
        boolean visibleWeb) implements ArticuloDatos {

    @AssertTrue(message = "la alícuota de IVA debe ser " + ALICUOTAS)
    public boolean isAlicuotaIvaValida() {
        return ArticuloDatos.alicuotaValida(alicuotaIva);
    }
}
