#!/usr/bin/env python3
"""Build versioned release assets without including developer data."""
import argparse
import gzip
import hashlib
from pathlib import Path
import shutil
import tarfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = (ROOT / "VERSION").read_text().strip()
RESOURCES = ["docker-compose-v4.yml", "docker-compose-v6.yml", "gost.sql",
             "springboot-backend/src/main/resources/db/upgrade_forward_chain.sql"]

def archive(path, entries):
    with path.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as zipped:
        with tarfile.open(fileobj=zipped, mode="w") as tar:
            for source, name in entries:
                info = tar.gettarinfo(str(source), arcname=name)
                info.uid = info.gid = 0
                info.uname = info.gname = ""
                info.mtime = 0
                info.mode = 0o755 if name.endswith(".sh") else 0o644
                with source.open("rb") as f:
                    tar.addfile(info, f)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "dist")
    parser.add_argument("--nodes", type=Path, default=ROOT / "artifacts")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    for name in ["panel_install.sh", "install.sh"]:
        shutil.copy2(ROOT / name, out / name)
    for arch in ["amd64", "arm64"]:
        name = "gost-linux-" + arch
        source = args.nodes / name
        if not source.is_file() or source.stat().st_size == 0:
            raise SystemExit("Build node binary first: " + str(source))
        shutil.copy2(source, out / name)
    archive(out / f"fluxchain-panel-v{VERSION}.tar.gz", [(ROOT / p, p) for p in RESOURCES])
    if args.offline:
        image = out / f"fluxchain-images-amd64-v{VERSION}.tar.gz"
        if not image.is_file():
            raise SystemExit("Image archive is missing: " + str(image))
        entries = [(ROOT / p, p) for p in RESOURCES + ["panel_install.sh", "install.sh", "LICENSE", "README.md",
                   "vite-frontend/LICENSE", "springboot-backend/src/main/resources/fonts/OFL.txt",
                   "docs/images/forward-chain-overview.png", "docs/images/forward-chain-create.png"]]
        entries += [(image, "images/panel-images.tar.gz")]
        entries += [(args.nodes / ("gost-linux-" + arch), "artifacts/gost-linux-" + arch) for arch in ["amd64", "arm64"]]
        archive(out / f"fluxchain-offline-amd64-v{VERSION}.tar.gz", entries)
    assets = sorted(p for p in out.iterdir() if p.is_file() and p.name != "SHA256SUMS")
    with (out / "SHA256SUMS").open("w", encoding="ascii", newline="\n") as manifest:
        for asset in assets:
            digest = hashlib.file_digest(asset.open("rb"), "sha256").hexdigest()
            manifest.write(f"{digest}  {asset.name}\n")
    print(f"Release v{VERSION}: {len(assets)} assets in {out}")

if __name__ == "__main__":
    main()
