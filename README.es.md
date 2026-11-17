# CommerceFlow

Backend original de Luciano Lattante con Java 21 y Spring Boot, desarrollado como **monolito modular**
con MySQL, Kafka, Redis y límites explícitos entre módulos.

**MVP local funcional verificado.** CI 44104045854 pasó **122 pruebas unitarias + 160 de integración
MySQL/Kafka/Redis**, sin fallos, errores ni omisiones. También pasaron el arranque con Docker Compose
y los flujos HTTP. La [evidencia](docs/VERIFICATION.md) identifica fuente, comandos, runtime y límites.

## Funcionalidad implementada

- Registro CUSTOMER, login bearer opaco, logout y roles ADMIN/CUSTOMER.
- Administración de usuarios con versión esperada, protección del último ADMIN activo y revocación/auditoría atómicas.
- Catálogo público filtrado/paginado; creación, revisión y desactivación ADMIN sin perder reservas.
- Órdenes propias con snapshots, dinero decimal y moneda explícita.
- Reserva concurrente de inventario, idempotencia por usuario y transactional outbox.
- Pagos simulados, cancelación y vencimiento con transición/stock/evento en una transacción.
- Kafka con leases, reintentos acotados, inbox idempotente, DLQ durable y recuperación ADMIN auditada.
- Admisión Redis con comportamiento de fallo explícito; MySQL conserva la autoridad sobre stock y órdenes.
- Auditorías, métricas y páginas administrativas que no leen payloads.
- Limpieza por lotes de tokens vencidos, preservando evidencia de negocio, replay e idempotencia.

## Ejecutar localmente

Requisitos: Docker Engine con Compose. Para compilar en host: Java 21 y Maven 3.6.3+.

```powershell
powershell -ExecutionPolicy Bypass -File scripts/init-env.ps1
docker compose up --build
```

El script genera credenciales aleatorias en .env sin sobrescribir archivos existentes. Si un entorno
anterior no tiene contraseña Redis independiente, usar scripts/add-redis-env.ps1. No se incluyen
credenciales predeterminadas. Los servicios escuchan en loopback; usar una base local aislada.

- API: <http://localhost:8080>
- OpenAPI: <http://localhost:8080/docs>
- Readiness: <http://localhost:8080/actuator/health/readiness>

El .env de Compose no se carga automáticamente al ejecutar Maven en host: exportar las variables
CF_* correspondientes. La limpieza de tokens está habilitada en Compose y deshabilitada por defecto
en host; sus límites/configuración están en [RETENTION.md](docs/RETENTION.md).
docker compose down conserva los volúmenes; eliminarlos borra los datos locales.

## Pruebas y presentación

```bash
mvn test
mvn verify
```

test ejecuta pruebas unitarias sin base de datos. verify agrega Testcontainers y requiere un daemon
Docker funcional; no omite silenciosamente la integración. Localmente pasaron 122 unitarias con
Oracle JDK 21.0.7/Maven 3.9.0. La integración y el flujo Compose se ejecutaron en CI.

La [demo reproducible](docs/DEMO.md) explica qué mostrar en entrevista y cómo observar cada garantía.
scripts/smoke-orders.py usa fixtures y credenciales de prueba contra loopback: ejecutarlo únicamente
en una instancia/base descartable. CI aporta valores sintéticos.

## Documentación técnica

| Tema | Contrato |
| --- | --- |
| Arquitectura y dominio | [ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Creación de órdenes | [ORDERS.md](docs/ORDERS.md) |
| Pago, cancelación y vencimiento | [LIFECYCLE.md](docs/LIFECYCLE.md) |
| Kafka, inbox, DLQ y recuperación | [MESSAGING.md](docs/MESSAGING.md) |
| Admisión, auditoría y métricas | [OPERABILITY.md](docs/OPERABILITY.md) |
| Administración de productos | [CATALOG_ADMINISTRATION.md](docs/CATALOG_ADMINISTRATION.md) |
| Administración de usuarios | [IDENTITY_ADMINISTRATION.md](docs/IDENTITY_ADMINISTRATION.md) |
| Retención y mantenimiento | [RETENTION.md](docs/RETENTION.md) |
| Caso de estudio y preguntas | [CASE_STUDY.md](docs/CASE_STUDY.md) |
| Decisiones y tradeoffs | [ADRs](docs/adr) |

## Límites

Los pagos son simulados. La entrega de eventos es al menos una vez; no se promete exactly-once externo.
No hay despliegue público, métricas de capacidad ni código/datos del empleador.
Recuperación/verificación de identidad, MFA/refresh tokens, retención completa con archival seguro,
trazas distribuidas, hardening/HA productivos y benchmarks siguen pendientes.
El [README en inglés](README.md) desarrolla ejemplos de API y alcance.
