# ADR 0001 — Start with a modular monolith

Status: accepted for the current increment.

CommerceFlow needs a maintainable commerce domain and will need atomic order, stock and outbox updates. Independent deployments would currently add operational failure modes without demonstrated scaling or ownership needs.

Use a single Java 21/Spring Boot application with identity, catalog, inventory domain and shared value objects. Add orders and integration modules incrementally. Keep controllers at the HTTP boundary and business rules in services/value objects.

Alternatives: microservices now would require distributed consistency and independent infrastructure before the business invariants are established. A single unstructured package would be simpler initially but would obscure responsibilities.

Tradeoff: one deployment and shared database remain coupled. Package visibility is a useful boundary, not an enforced multi-module build. Hexagonal ports will be introduced at actual external-provider/messaging boundaries; this first implementation does not claim a complete hexagonal architecture.

Version basis: Spring Boot 3.5.16 supports Java 21; springdoc 2.10.0 is within its published 3.5 compatibility range. Testcontainers 2.0.5 modules were selected from official documentation.

References: [Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [springdoc compatibility](https://springdoc.org/v2/), [Testcontainers MySQL](https://java.testcontainers.org/modules/databases/mysql/).
