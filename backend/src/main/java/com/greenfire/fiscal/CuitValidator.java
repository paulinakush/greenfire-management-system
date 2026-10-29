package com.greenfire.fiscal;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CuitValidator implements ConstraintValidator<Cuit, String> {

    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    @Override
    public boolean isValid(String cuit, ConstraintValidatorContext context) {
        return cuit == null || esValido(cuit);
    }

    public static boolean esValido(String cuit) {
        if (cuit == null || !cuit.matches("\\d{11}")) {
            return false;
        }
        int suma = 0;
        for (int i = 0; i < PESOS.length; i++) {
            suma += (cuit.charAt(i) - '0') * PESOS[i];
        }
        int verificador = 11 - suma % 11;
        if (verificador == 11) {
            verificador = 0;
        }
        return verificador != 10 && verificador == cuit.charAt(10) - '0';
    }
}
