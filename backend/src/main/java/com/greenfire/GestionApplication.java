package com.greenfire;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// Sin el usuario en memoria que Spring Security crea por defecto: el login es propio (seguridad.LoginService).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class GestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(GestionApplication.class, args);
    }
}
