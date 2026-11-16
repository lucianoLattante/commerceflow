package dev.lattante.commerceflow.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<AppUser, String> {
  Optional<AppUser> findByEmail(String email);
}
