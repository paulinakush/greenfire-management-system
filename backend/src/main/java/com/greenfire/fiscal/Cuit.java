package com.greenfire.fiscal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** CUIT de 11 dígitos, sin guiones, con dígito verificador válido. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CuitValidator.class)
public @interface Cuit {

    String message() default "CUIT inválido: deben ser 11 dígitos sin guiones, con dígito verificador correcto.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
