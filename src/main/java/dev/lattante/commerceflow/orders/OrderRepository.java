package dev.lattante.commerceflow.orders;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

interface OrderRepository extends JpaRepository<PurchaseOrder, String> {
  Page<PurchaseOrder> findByCustomerId(String customerId, Pageable pageable);

  Page<PurchaseOrder> findByCustomerIdAndStatus(
      String customerId, OrderStatus status, Pageable pageable);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select o from PurchaseOrder o where o.id=:id")
  Optional<PurchaseOrder> lockById(@Param("id") String id);

  @Query("""
      select o.id from PurchaseOrder o
      where o.status=dev.lattante.commerceflow.orders.OrderStatus.RESERVED and o.expiresAt<=:now
      order by o.expiresAt,o.id
      """)
  List<String> dueIds(@Param("now") Instant now, Pageable limit);
}
