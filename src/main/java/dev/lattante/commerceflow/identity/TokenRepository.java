package dev.lattante.commerceflow.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TokenRepository extends JpaRepository<AccessToken, String> {
  @Modifying
  @Query("delete from AccessToken t where t.user.id=:id")
  int deleteForUser(@Param("id") String id);

  @EntityGraph(attributePaths = "user")
  Optional<AccessToken> findByDigest(String digest);
}
