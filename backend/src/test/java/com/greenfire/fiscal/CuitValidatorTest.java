package com.greenfire.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.greenfire.Datos;

class CuitValidatorTest {

    @Test
    void aceptaUnCuitConDigitoVerificadorCorrecto() {
        assertThat(CuitValidator.esValido("20123456786")).isTrue();
        assertThat(CuitValidator.esValido("30500010912")).isTrue();
        assertThat(CuitValidator.esValido(Datos.cuit())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20123456787", "20-12345678-6", "2012345678", "201234567861", "2012345678A", ""})
    void rechazaCuitsMalFormadosOConDigitoIncorrecto(String cuit) {
        assertThat(CuitValidator.esValido(cuit)).isFalse();
    }
}
