# CommerceFlow

Backend original de Luciano Lattante con Java 21, Spring Boot y MySQL, desarrollado como monolito modular.

**Primer incremento:** registro como CUSTOMER, autenticación bearer opaca con revocación, creación de productos por ADMIN, catálogo público con búsqueda/paginación, migraciones Flyway, errores, métricas y OpenAPI.

El incremento de órdenes incluye snapshots, propiedad, reserva transaccional, idempotencia por usuario y persistencia de outbox. CF-04 agrega pagos simulados aprobados/rechazados, cancelación y vencimiento de reservas a los 15 minutos, con stock y evento en una transacción. El [ciclo de órdenes](docs/LIFECYCLE.md) explica reintentos, permisos y límites. Kafka y Redis siguen pendientes; el MVP distribuido completo continúa en desarrollo.

El [README principal](README.md) contiene los comandos reproducibles de Docker Compose y Maven, ejemplos de API, límites y documentación. El script scripts/init-env.ps1 genera credenciales locales aleatorias y no sobrescribe .env.

En el incremento anterior se ejecutaron 54 pruebas unitarias con Java 21.0.7 y Maven 3.9.0. Las pruebas MySQL/Testcontainers necesitan Docker y se verifican en CI; su resultado debe consultarse en la ejecución correspondiente. CF-04 pasó en CI: 65 pruebas unitarias y 78 de integración MySQL, sin fallos, errores ni pruebas omitidas; también pasó el flujo HTTP con Docker Compose. La evidencia exacta está en [VERIFICATION.md](docs/VERIFICATION.md). No se ejecutaron nuevas pruebas locales mientras la PC estuvo desconectada. No hay dinero real ni despliegue público.
