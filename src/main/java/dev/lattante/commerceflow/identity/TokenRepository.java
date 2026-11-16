package dev.lattante.commerceflow.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface TokenRepository extends JpaRepository<AccessToken, String> {
  @EntityGraph(attributePaths = "user")
  Optional<AccessToken> findByDigest(String digest);
}
