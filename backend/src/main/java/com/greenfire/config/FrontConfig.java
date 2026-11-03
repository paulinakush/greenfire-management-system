package com.greenfire.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sirve el front (index.html, assets/, img/, privado/) desde la raíz del repo.
 * Se registran solo esas rutas para no exponer docs/, backend/, .env ni .git.
 */
@Configuration
public class FrontConfig implements WebMvcConfigurer {

    private final String frontDir;

    public FrontConfig(@Value("${greenfire.front.dir}") String frontDir) {
        this.frontDir = Path.of(frontDir).toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html").addResourceLocations(frontDir);
        registry.addResourceHandler("/assets/**").addResourceLocations(frontDir + "assets/");
        registry.addResourceHandler("/img/**").addResourceLocations(frontDir + "img/");
        registry.addResourceHandler("/privado/**").addResourceLocations(frontDir + "privado/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
