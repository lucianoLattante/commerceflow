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

El archivo a utilizar es **`.env` junto a `compose.yml` y `pom.xml` de CommerceFlow**. `.env.example`
es una referencia y `scripts/init-env.ps1` genera los valores locales cuando no existe `.env`.
Cada copia del proyecto tiene su propio archivo: ejecutar desde la carpeta correcta o usar
`--env-file .env -f compose.yml` explícitamente. Conservar el archivo existente y sus contraseñas.
Las variables del shell pueden prevalecer sobre las del archivo.

- `CF_DB_PASSWORD`: credencial de la aplicación en MySQL.
- `CF_DB_ROOT_PASSWORD`: credencial distinta del administrador de MySQL.
- `CF_REDIS_PASSWORD`: credencial independiente de Redis.
- `CF_BOOTSTRAP_ADMIN_EMAIL` y `CF_BOOTSTRAP_ADMIN_PASSWORD`: login del administrador inicial.
- `CF_APP_PORT` y `CF_DB_PORT`: puertos publicados opcionales; defaults del repositorio 8080 y 3306.

CommerceFlow usa tokens opacos emitidos por `/api/auth/token`; no necesita claves de Google, JWT,
Mercado Pago ni el `.env` de Crypto Alert AI para simular pagos. Compose configura los hosts internos
`mysql`, `kafka` y `redis`. Agregar una variable a `.env` no la pasa automáticamente al contenedor:
debe estar referenciada en `compose.yml`.

Desde la carpeta que contiene compose.yml y pom.xml:

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    powershell -ExecutionPolicy Bypass -File scripts/init-env.ps1
}
docker compose --env-file .env -f compose.yml config --quiet
docker compose --env-file .env -f compose.yml up -d --build --wait --wait-timeout 240
docker compose ps
powershell -ExecutionPolicy Bypass -File scripts/check-local.ps1
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

Los enlaces siguientes usan el puerto predeterminado 8080. Si `docker compose ps` muestra
`127.0.0.1:8081->8080/tcp`, abrir **http://localhost:8081/docs** y usar 8081 en las peticiones.
8080 a la derecha es el puerto interno del contenedor. `scripts/check-local.ps1` muestra el puerto
publicado y comprueba la configuración sin imprimir secretos.

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

### Si simulated-payment devuelve 400

Usar el mismo token del propietario de la orden, el **UUID completo de la orden** (no el UUID del
producto ni el texto `string`), y `Content-Type: application/json`. El header `Idempotency-Key` es
obligatorio aunque el token sea válido: cargar `demo-payment-001` en el campo del header de Swagger.
El body completo es `{"decision":"APPROVED"}` o `{"decision":"DECLINED"}`, respetando mayúsculas.
No enviar `PAID` como decisión.

Los ejemplos de esta guía (`demo-payment-001` y `APPROVED`) cumplen el contrato. Una respuesta 400
requiere revisar su `detail`: indica el header faltante, UUID inválido, campo requerido, valores
permitidos o JSON malformado, sin reproducir tokens ni valores recibidos. Una clave como `string`
tiene solo seis caracteres y no cumple la longitud mínima de ocho.

```powershell
$baseUrl = 'http://localhost:8080' # Usar el puerto publicado; por ejemplo 8081
$orderId = '<UUID devuelto al crear la orden>'
# $accessToken: accessToken obtenido con POST /api/auth/token; mantenerlo privado
$headers = @{ Authorization = "Bearer $accessToken"; 'Idempotency-Key' = 'demo-payment-001' }
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/orders/$orderId/simulated-payment" `
    -Headers $headers -ContentType 'application/json' -Body '{"decision":"APPROVED"}'
```

| Resultado | Qué comprobar |
| --- | --- |
| 200 PAID | Simulación aprobada; revisar también el estado del body |
| 200 EXPIRED | Reserva vencida; crear otra orden (el login nuevo no renueva la reserva) |
| 400 invalid_request | `detail`, UUID, JSON, decisión y `Idempotency-Key` |
| 401 | Login nuevo, token vigente y Authorize |
| 404 | UUID real y propietario correcto; ADMIN tampoco paga órdenes de otro usuario |
| 409 | Orden terminal o clave/decisión distinta de un pago ya realizado |
| 429 | Esperar `Retry-After` antes de repetir |
| 503 | Readiness y dependencias; reintentar según el contrato de idempotencia |
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

## 11. Comandos esenciales de Docker

Ejecutar desde esta carpeta con el mismo `.env`:

| Comando | Uso |
| --- | --- |
| `docker version` | Cliente y motor disponibles |
| `docker compose config --quiet` | Validar configuración sin expandir secretos en pantalla |
| `docker compose up -d --build` | Construir/actualizar y levantar el stack |
| `docker compose ps` | Estado y puertos publicados |
| `docker compose logs -f --tail 100 app` | Seguir errores de la API; Ctrl+C deja de seguir el log |
| `docker compose logs --tail 100 mysql kafka redis` | Diagnóstico de dependencias |
| `docker compose port app 8080` | Puerto real para conectarse desde la PC |
| `docker compose stop` | Detener conservando contenedores y volúmenes |
| `docker compose start` | Iniciar contenedores detenidos sin aplicar cambios de configuración |
| `docker compose restart app` | Reiniciar la API con la configuración ya creada |
| `docker compose up -d --force-recreate app` | Aplicar cambios del entorno al contenedor |
| `docker compose down` | Quitar contenedores/red y conservar datos |

Después de modificar código usar `up -d --build`; un `restart` no reconstruye la imagen.
Después de modificar `.env`, recrear el contenedor afectado: `restart` no cambia sus variables.
Cambiar una contraseña MySQL en `.env` no cambia la almacenada en un volumen existente.
El `config` sin `--quiet`, `config --environment` y `docker inspect` completo pueden mostrar secretos;
compartir solo el código HTTP, `detail` y logs revisados/redactados.
