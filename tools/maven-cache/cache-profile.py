#!/usr/bin/env python3
"""Prepare or validate an opt-in cache through the pinned Maven Resolver SPI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

PROFILE = "maven-3.9.16-resolver-1.9.27-origin-v1"
SOURCE = Path(__file__).with_name("OriginFactory.java")
IMAGE = re.compile(r"[A-Za-z0-9._/:@-]+@sha256:[0-9a-f]{64}")

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def regular(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError("Cache profile input is not a regular file: " + str(path))
    return path

def validate(directory, image):
    if any(c in str(directory) for c in ",\r\n\t"):
        raise ValueError("Cache mount path contains unsupported separators")
    if not IMAGE.fullmatch(image):
        raise ValueError("Cache profile requires an immutable image digest")
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Cache profile is not a regular directory")
    directory = directory.resolve(strict=True)
    config = json.loads(regular(directory / "profile.json").read_text())
    if config.get("profile") != PROFILE or config.get("image") != image:
        raise ValueError("Cache runtime profile/image mismatch")
    if config.get("sourceSha256") != digest(SOURCE):
        raise ValueError("Cache extension source identity mismatch")
    if config.get("extensionSha256") != digest(regular(directory / "extension.jar")):
        raise ValueError("Cache extension bytes do not match profile")
    repository = directory / "repository"
    if repository.is_symlink() or not repository.is_dir():
        raise ValueError("Cache repository is not a regular directory")
    return directory

def prepare(directory, image):
    if not IMAGE.fullmatch(image):
        raise ValueError("Cache preparation requires an immutable image digest")
    if any(c in str(directory) for c in ",\r\n\t"):
        raise ValueError("Cache mount path contains unsupported separators")
    directory.mkdir(parents=True, exist_ok=False)
    directory = directory.resolve(strict=True)
    (directory / "repository").mkdir()
    # Tool input is copied, not local repository layout/artifacts.
    (directory / "OriginFactory.java").write_bytes(SOURCE.read_bytes())
    name = "conformance-maven-cache-build-" + str(os.getpid())
    script = r'''set -eu
mkdir -p /workspace/profile/classes/META-INF/sisu
javac --release 17 -cp "/usr/share/maven/lib/*" -d /workspace/profile/classes /workspace/profile/OriginFactory.java
java -cp "/workspace/profile/classes:/usr/share/maven/lib/*" org.metamodel.cache.OriginFactory
printf "%s\n" org.metamodel.cache.OriginFactory > /workspace/profile/classes/META-INF/sisu/javax.inject.Named
jar cf /workspace/profile/extension.jar -C /workspace/profile/classes .
'''
    command = [
        "docker", "run", "--rm", "--name", name, "--read-only", "--entrypoint", "bash",
        "--network", "none", "--hostname", "localhost", "--cap-drop=ALL",
        "--security-opt=no-new-privileges", "--tmpfs", "/tmp:rw,nosuid,nodev,noexec",
        "--user", str(os.getuid()) + ":" + str(os.getgid()),
        "--mount", "type=bind,src=" + str(directory) + ",dst=/workspace/profile",
        image, "-c", script,
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
    except subprocess.TimeoutExpired:
        subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        raise
    (directory / "build.txt").write_text(result.stdout + result.stderr)
    if result.returncode != 0 or PROFILE not in result.stdout.splitlines():
        raise ValueError("Unsupported or failed cache build:\n" + result.stdout + result.stderr)
    image_id = subprocess.check_output(["docker", "image", "inspect", "--format", "{{.Id}}", image], text=True, timeout=20).strip()
    config = dict(profile=PROFILE, image=image, imageId=image_id,
                  sourceSha256=digest(SOURCE), extensionSha256=digest(directory / "extension.jar"))
    (directory / "profile.json").write_text(json.dumps(config, indent=2) + "\n")
    return validate(directory, image)

def check_receipt(path, attempt):
    value = json.loads(regular(path).read_text())
    if value != dict(profile=PROFILE, attempt=attempt):
        raise ValueError("Maven did not select the requested cache profile/attempt")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("prepare", "validate"):
        cmd = commands.add_parser(name)
        cmd.add_argument("directory", type=Path)
        cmd.add_argument("image")
    receipt = commands.add_parser("receipt")
    receipt.add_argument("path", type=Path)
    receipt.add_argument("attempt")
    args = parser.parse_args()
    try:
        if args.command == "receipt":
            check_receipt(args.path, args.attempt)
        else:
            print((prepare if args.command == "prepare" else validate)(args.directory, args.image))
    except (ValueError, OSError, json.JSONDecodeError, subprocess.SubprocessError) as error:
        parser.exit(70, str(error) + "\n")

if __name__ == "__main__":
    main()
