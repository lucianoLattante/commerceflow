"""Guard the dependency directions that keep inventory and broker I/O out of order internals."""
from pathlib import Path
import re

root = Path("src/main/java/dev/lattante/commerceflow")
assert root.is_dir(), root
significant = {
    "inventory": ("dev.lattante.commerceflow.orders.", "dev.lattante.commerceflow.messaging.",
                  "org.springframework.kafka.", "org.springframework.data.redis."),
    "orders": ("dev.lattante.commerceflow.messaging.", "org.springframework.kafka.",
               "org.apache.kafka.", "org.springframework.data.redis.", "io.lettuce."),
}
violations = []
for module, forbidden in significant.items():
    files = sorted((root / module).rglob("*.java"))
    assert files, module
    for file in files:
        source = file.read_text(encoding="utf-8")
        for name in re.findall(r"(?m)^\s*import\s+(?:static\s+)?([\w.*]+)\s*;", source):
            if name.startswith(forbidden):
                violations.append(f"{file.as_posix()}: {name}")
if violations:
    raise SystemExit("Forbidden module dependencies:\n" + "\n".join(violations))
print("Inventory and order module import boundaries passed.")
