package com.TrucVanban.auth.repository;

import com.TrucVanban.auth.entity.User;
import com.TrucVanban.auth.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsernameAndStatus(String username, UserStatus status);
}
