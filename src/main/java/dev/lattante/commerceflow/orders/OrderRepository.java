package dev.lattante.commerceflow.orders;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<PurchaseOrder, String> {
  Page<PurchaseOrder> findByCustomerId(String customerId, Pageable pageable);

  Page<PurchaseOrder> findByCustomerIdAndStatus(
      String customerId, OrderStatus status, Pageable pageable);
}
