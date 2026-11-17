package dev.lattante.commerceflow.identity;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<AppUser, String>, JpaSpecificationExecutor<AppUser> {
  Optional<AppUser> findByEmail(String email);

  long countByRoleAndActiveTrue(AppUser.Role role);

  @Query(value = "SELECT id FROM identity_admin_guard WHERE id=1 FOR UPDATE", nativeQuery = true)
  Optional<Integer> lockAdministration();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from AppUser u where u.id=:id")
  Optional<AppUser> lockById(@Param("id") String id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from AppUser u where u.email=:email")
  Optional<AppUser> lockByEmail(@Param("email") String email);
}
