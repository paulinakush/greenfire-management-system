package com.greenfire.usuarios;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    @Query("select u from Usuario u where lower(u.email) = lower(?1)")
    Optional<Usuario> buscarPorEmail(String email);

    @Query("select count(u) > 0 from Usuario u where lower(u.email) = lower(?1)")
    boolean existeEmail(String email);

    @Query("select count(u) > 0 from Usuario u where lower(u.email) = lower(?1) and u.id <> ?2")
    boolean existeEmailEnOtro(String email, Long id);

    boolean existsByEsAdminTecnicoTrue();

    Optional<Usuario> findByResetTokenHash(String resetTokenHash);

    List<Usuario> findByEsAdminTecnicoFalseOrderByNombre();
}
