# Probar CommerceFlow en local (Windows)

CommerceFlow es una API backend. No tiene una tienda web/frontend. La interfaz para explorar y enviar
peticiones es Swagger UI; también se puede usar Postman o PowerShell. Los pagos son simulados.

## 1. Comprobar Docker

Abrir Docker Desktop desde Inicio y esperar que el motor esté listo. En una PowerShell normal:

```powershell
docker version
docker compose version
```

docker version debe mostrar tanto Client como Server. Tener solamente el cliente o el plugin Compose
no alcanza. Si aparece docker_engine ... pipe ... not found, el motor todavía no está disponible.
Si Docker muestra un error de WSL/permisos, resolverlo desde su interfaz y la
[documentación oficial de Windows](https://docs.docker.com/desktop/setup/install/windows-install/).
Un diagnóstico WSL denegado desde un agente no demuestra por sí solo que WSL falte en la PC.
Este proyecto utiliza contenedores Linux.

## 2. Arrancar el stack

Desde la carpeta que contiene compose.yml y pom.xml:

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    powershell -ExecutionPolicy Bypass -File scripts/init-env.ps1
}
docker compose up -d --build --wait --wait-timeout 240
docker compose ps
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
```

El primer build descarga imágenes/dependencias. Con Compose no es necesario instalar Java, Maven,
MySQL, Kafka o Redis en el host. Se levantan aplicación, MySQL, Kafka y Redis; usar una base aislada.
Si se agota el tiempo de espera, revisar docker compose ps y docker compose logs --tail 100 app.
No borrar volúmenes como primer intento de solución. Un puerto ocupado requiere resolver el conflicto,
sin detener aplicaciones ajenas automáticamente.

El generador conserva .env existentes. Para un entorno anterior sin contraseña Redis independiente,
usar scripts/add-redis-env.ps1. No regenerar contraseñas para un volumen MySQL ya inicializado:
cambiar el archivo no cambia las credenciales guardadas en la base. Conservar la configuración original.

## 3. Abrir Swagger

- Swagger UI: <http://localhost:8080/docs>
- Catálogo público: <http://localhost:8080/api/products>
- Readiness: <http://localhost:8080/actuator/health/readiness>

Readiness debe devolver status UP antes de probar el flujo. Abrir / a secas puede devolver 404:
no existe una página de inicio de tienda. En Swagger, desplegar un endpoint, usar Try it out y Execute.

## 4. Login de administrador

Abrir el .env local en un editor y consultar CF_BOOTSTRAP_ADMIN_EMAIL y CF_BOOTSTRAP_ADMIN_PASSWORD.
No compartir el archivo, pegar sus valores en issues ni subirlo a Git. El usuario creado por el script
es admin@example.test; su contraseña es aleatoria, no admin/admin.

En POST /api/auth/token:

```json
{"email":"admin@example.test","password":"<la-contraseña-local-generada>"}
```

Copiar accessToken. En Authorize, pegar solamente ese token: Swagger agrega Bearer. Los tokens vencen
a los 15 minutos; ante 401, repetir el login. Logout o cambios administrativos también pueden revocarlos.

## 5. Crear un producto

Con el token ADMIN, ejecutar POST /api/products:

```json
{"sku":"DEMO-001","name":"Teclado de prueba","amount":1299.90,"currency":"ARS","stock":10}
```

Esperar 201 y copiar el id. GET /api/products/{id} debe mostrar available=10.
Si el SKU ya existe, usar otro o reutilizar el producto existente; 409 por duplicado es esperado.

## 6. Crear una orden y repetirla

Para un primer recorrido se puede usar el mismo ADMIN como propietario de la orden.
En POST /api/orders, cargar el header Idempotency-Key con demo-order-001 y este body:

```json
{"items":[{"productId":"<id-del-producto>","quantity":2}]}
```

Esperar 201, status RESERVED y total 2599.80 ARS. Copiar el id de la orden.
Repetir la petición con la misma clave y el mismo body: debe devolver 200, el mismo id y el header
Idempotency-Replayed=true. La disponibilidad baja a 8 una sola vez.
Reutilizar la clave con cantidades distintas devuelve 409. Para una orden nueva, usar otra clave.

## 7. Pagar y consultar la evidencia

En POST /api/orders/{id}/simulated-payment:
- id: el de la orden.
- Idempotency-Key: demo-payment-001.

```json
{"decision":"APPROVED"}
```

La orden pasa a PAID. La disponibilidad sigue en 8: el pago consume la reserva, no descuenta dos veces.
Repetir el mismo pago/clave no debe generar otra transición.
GET /api/orders/{id}/audit muestra la reserva y el pago; GET /api/orders/{id}/projection llega
asíncronamente a PAID con sequence=2 cuando Kafka procesa los eventos. Puede requerir volver a consultar.

Para cancelar, crear otra orden con clave nueva y usar POST /api/orders/{id}/cancel antes de pagarla.
Para rechazo, crear otra y enviar decision DECLINED con clave de pago nueva. Cancelación/rechazo liberan
sus reservas. No se puede cancelar una orden ya pagada. Una reserva vence a los 15 minutos; el worker
la procesa después, sin SLA exacto. Para el recorrido de pago, no esperar ese vencimiento.

## 8. Comprobar roles

POST /api/auth/register crea CUSTOMER, aunque se intente enviar otro rol.
Usar un email de prueba distinto y una contraseña de 12 o más caracteres (máximo 72 bytes UTF-8),
hacer login y reemplazar el token en Authorize. CUSTOMER puede crear/ver sus órdenes; no puede crear
productos ni consultar /api/admin/operations/maintenance. Esto último devuelve 403.
Sin token, una operación protegida devuelve 401. Una orden ajena no se expone al otro CUSTOMER.
Volver a autorizar el token ADMIN para las rutas administrativas.

## 9. Pruebas automáticas

```powershell
mvn test
mvn verify
```

Estas dos órdenes requieren Java 21 y Maven en el host. test ejecuta unitarias; verify agrega
Testcontainers y requiere el motor Docker. Para verificar todo en contenedores sin Maven local,
también se puede consultar o ejecutar el workflow del repositorio en GitHub Actions.

scripts/smoke-orders.py es el recorrido HTTP automatizado de CI. Requiere Python y las variables
CF_BOOTSTRAP_ADMIN_EMAIL/CF_BOOTSTRAP_ADMIN_PASSWORD exportadas en su proceso: el script Python no
carga .env automáticamente. Crea fixtures y realiza cambios de roles/estado; ejecutarlo solo sobre
una instancia/base descartable. Puede agotar temporalmente la cuota de login de ese socket durante
su comprobación 429. Para probar manualmente, Swagger es suficiente y no requiere ejecutar ese script.

La evidencia vigente y los comandos de CI están en [VERIFICATION.md](VERIFICATION.md).
No confundir pruebas pasadas en CI con pruebas ejecutadas en la PC.

## 10. Detener sin perder datos

```powershell
docker compose stop
# Para volver a levantar:
docker compose up -d
```

docker compose down detiene y elimina contenedores/red, conservando volúmenes.
down --volumes elimina datos locales; no se necesita para este recorrido.
