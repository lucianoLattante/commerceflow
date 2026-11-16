# CommerceFlow

Backend original de Luciano Lattante con Java 21, Spring Boot y MySQL, desarrollado como monolito modular.

**Primer incremento:** registro como CUSTOMER, autenticación bearer opaca con revocación, creación de productos por ADMIN, catálogo público con búsqueda/paginación, migraciones Flyway, errores, métricas y OpenAPI.

El nuevo incremento implementa órdenes con snapshots, propiedad, reserva transaccional, idempotencia por usuario y persistencia de outbox. El [contrato de órdenes](docs/ORDERS.md) distingue lo implementado de lo pendiente: pagos/cancelación/expiración, Kafka y Redis. El MVP completo sigue en desarrollo.

El [README principal](README.md) contiene los comandos reproducibles de Docker Compose y Maven, ejemplos de API, límites y documentación. El script scripts/init-env.ps1 genera credenciales locales aleatorias y no sobrescribe .env.

Se ejecutaron 53 pruebas unitarias con Java 21.0.7 y Maven 3.9.0. Las pruebas MySQL/Testcontainers necesitan Docker y se verifican en CI; su resultado debe consultarse en la ejecución correspondiente. No se declara un despliegue público.
