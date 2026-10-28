#!/usr/bin/env python3
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
version = (root / "VERSION").read_text().strip()
assert re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version), version
package = json.loads((root / "vite-frontend/package.json").read_text())
assert package["version"] == version
pom = ET.parse(root / "springboot-backend/pom.xml").getroot()
assert pom.find("{http://maven.apache.org/POM/4.0.0}version").text == version
for name in ["panel_install.sh", "install.sh"]:
    assert f'RELEASE_VERSION="{version}"' in (root / name).read_text(encoding="utf-8"), name
assert f'nodeAgentVersion = "{version}"' in (root / "go-gost/version.go").read_text()
assert f'FluxChain v{version}' in (root / "springboot-backend/src/main/resources/banner.txt").read_text()
for name in ["docker-compose-v4.yml", "docker-compose-v6.yml"]:
    text = (root / name).read_text(encoding="utf-8")
    for component in ["backend", "frontend"]:
        assert f'ghcr.io/toto0912/flux-chain-{component}:{version}' in text
assert f'/releases/download/v{version}/install.sh' in (root / "springboot-backend/src/main/java/com/admin/service/impl/NodeServiceImpl.java").read_text(encoding="utf-8")
if len(sys.argv) > 1:
    assert sys.argv[1] == "v" + version, "Tag and application versions differ"
print("PASS release version consistency:", version)
