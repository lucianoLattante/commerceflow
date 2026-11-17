package dev.lattante.commerceflow.catalog;

import dev.lattante.commerceflow.inventory.InventoryReservation;
import dev.lattante.commerceflow.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class JpaInventoryReservation implements InventoryReservation {
  private final ProductRepository products;

  JpaInventoryReservation(ProductRepository products) {
    this.products = products;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<ReservedItem> reserve(List<Demand> demands) {
    var ordered =
        demands.stream().sorted(Comparator.comparing(d -> d.productId().toString())).toList();
    List<ReservedItem> result = new ArrayList<>();
    for (Demand demand : ordered) {
      Product product = products.lockById(demand.productId().toString()).orElseThrow(NotFound::new);
      product.reserve(demand.quantity());
      result.add(
          new ReservedItem(
              demand.productId(),
              product.getSku(),
              product.getName(),
              new Money(product.getAmount(), product.getCurrency()),
              demand.quantity()));
    }
    return List.copyOf(result);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void release(List<Demand> demands) {
    adjust(demands, false);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void consume(List<Demand> demands) {
    adjust(demands, true);
  }

  private void adjust(List<Demand> demands, boolean consume) {
    for (Demand demand : demands.stream()
        .sorted(Comparator.comparing(d -> d.productId().toString())).toList()) {
      Product product = products.lockById(demand.productId().toString()).orElseThrow(NotFound::new);
      if (consume) product.consume(demand.quantity());
      else product.release(demand.quantity());
    }
  }
}
