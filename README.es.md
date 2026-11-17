# CommerceFlow

Backend original de Luciano Lattante con Java 21, Spring Boot y MySQL, desarrollado como monolito modular.

**Primer incremento:** registro como CUSTOMER, autenticación bearer opaca con revocación, creación de productos por ADMIN, catálogo público con búsqueda/paginación, migraciones Flyway, errores, métricas y OpenAPI.

El incremento de órdenes incluye snapshots, propiedad, reserva transaccional, idempotencia por usuario y persistencia de outbox. CF-04 agrega pagos simulados aprobados/rechazados, cancelación y vencimiento de reservas a los 15 minutos, con stock y evento en una transacción. El [ciclo de órdenes](docs/LIFECYCLE.md) explica reintentos, permisos y límites. CF-05 incorpora Kafka, publicación con leases/reintentos, inbox/proyección idempotente, DLQ durable y replay administrativo auditado. La verificación se registra en CI. CF-06 incorpora Redis para limitar login/registros/escrituras, auditoría transaccional de órdenes y métricas de operaciones confirmadas; su alcance está en [OPERABILITY.md](docs/OPERABILITY.md). La administración de usuarios se detalla abajo; la retención sigue pendiente. Ver [MESSAGING.md](docs/MESSAGING.md).

El [README principal](README.md) contiene los comandos reproducibles de Docker Compose y Maven, ejemplos de API, límites y documentación. El script scripts/init-env.ps1 genera credenciales locales aleatorias y no sobrescribe .env.

En el incremento anterior se ejecutaron 54 pruebas unitarias con Java 21.0.7 y Maven 3.9.0. Las pruebas MySQL/Testcontainers necesitan Docker y se verifican en CI; su resultado debe consultarse en la ejecución correspondiente. CF-04 pasó en CI: 65 pruebas unitarias y 78 de integración MySQL, sin fallos, errores ni pruebas omitidas; también pasó el flujo HTTP con Docker Compose. La evidencia exacta está en [VERIFICATION.md](docs/VERIFICATION.md). No se ejecutaron nuevas pruebas locales mientras la PC estuvo desconectada. No hay dinero real ni despliegue público.

CF-05 pasó en CI: **82 pruebas unitarias y 95 de integración MySQL/Kafka**, sin fallos, errores ni pruebas omitidas. Docker Compose y el flujo HTTP también verificaron las proyecciones asíncronas. La ejecución exacta está en [VERIFICATION.md](docs/VERIFICATION.md).

CF-06 pasó localmente 90 pruebas unitarias y en CI 90 unitarias + 108 de integración MySQL/Kafka/Redis, sin fallos, errores ni omisiones. También pasaron Docker Compose y el flujo HTTP de auditoría/admisión. Se preservó la copia local anterior y se preparó una fuente de trabajo nueva del main verificado.

## Administración del catálogo

El nuevo incremento permite editar, desactivar/reactivar y reconciliar existencias con versión esperada,
bloqueo compartido con las reservas y auditoría en la misma transacción. Conserva precios de órdenes ya
creadas y permite finalizar sus reservas aunque el producto esté inactivo. El [contrato de API](docs/CATALOG_ADMINISTRATION.md)
explica los conflictos y reintentos; no se presenta como administración de usuarios ni libro de inventario.
Verificación: 99 pruebas unitarias locales y CI 44089250548 con 99 unitarias + 123 de integración MySQL/Kafka/Redis, sin fallos, errores ni omisiones. Docker Compose y el flujo HTTP de administración también pasaron. La evidencia exacta está en [VERIFICATION.md](docs/VERIFICATION.md).

## Administración de usuarios

Se implementaron consultas ADMIN y cambios de rol/activación con versión esperada, auditoría y
revocación de tokens en una transacción. Un bloqueo en MySQL conserva al menos un administrador
activo; el login comparte el bloqueo del usuario para coordinar emisión y revocación. Promover o
reactivar exige autenticarse nuevamente. El [contrato](docs/IDENTITY_ADMINISTRATION.md) documenta
concurrencia, bootstrap y límites. Pasaron 108 unitarias locales; integración y Compose pendientes de CI.
No se presenta como ciclo de identidad completo: recuperación/verificación, refresh y retención siguen pendientes.
