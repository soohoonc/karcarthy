"""Local-development Harbor adapter for the packaged karcarthy Coding Agent.

Harbor's public ACP registry intentionally requires HTTPS distribution URLs.
This adapter changes only the installation step: it uploads a locally built
archive into the task environment, then delegates execution, logging, and ATIF
conversion to Harbor's generic AcpAgent implementation.
"""

import asyncio
import os
import shutil
import urllib.request
import uuid
from pathlib import Path
from typing import Any

from harbor.agents.installed import acp as harbor_acp
from harbor.agents.installed.acp import AcpAgent


class Agent(AcpAgent):
    _JRE_URLS = {
        "linux-aarch64": (
            "https://api.adoptium.net/v3/binary/latest/21/ga/linux/"
            "aarch64/jre/hotspot/normal/eclipse"
        ),
        "linux-x86_64": (
            "https://api.adoptium.net/v3/binary/latest/21/ga/linux/"
            "x64/jre/hotspot/normal/eclipse"
        ),
    }

    def __init__(
        self,
        archive_path: str,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        self._local_archive_path = Path(archive_path).resolve()
        if not self._local_archive_path.is_file():
            raise ValueError(
                f"karcarthy ACP archive does not exist: {self._local_archive_path}"
            )
        target = {
            "archive": "https://example.invalid/karcarthy-harbor.tgz",
            "cmd": "./karcarthy",
        }
        registry_entry = {
            "id": "karcarthy-coding-agent",
            "name": "karcarthy Coding Agent",
            "version": "0.0.3",
            "description": "Locally packaged Clojure Coding Agent",
            "distribution": {
                "binary": {
                    "linux-aarch64": target,
                    "linux-x86_64": target,
                }
            },
        }
        super().__init__(registry_entry=registry_entry, *args, **kwargs)

    @classmethod
    def _jre_archive(cls, platform_id: str) -> Path:
        url = cls._JRE_URLS.get(platform_id)
        if not url:
            raise ValueError(f"No Java 21 runtime for Harbor platform: {platform_id}")
        cache = Path.home() / ".cache" / "karcarthy"
        cache.mkdir(parents=True, exist_ok=True)
        archive = cache / f"temurin-21-jre-{platform_id}.tar.gz"
        if archive.is_file() and archive.stat().st_size > 1_000_000:
            return archive
        temporary = cache / f".{archive.name}.{os.getpid()}.{uuid.uuid4().hex}"
        try:
            request = urllib.request.Request(
                url, headers={"User-Agent": "karcarthy-harbor/0.0.3"}
            )
            with urllib.request.urlopen(request) as response:
                with temporary.open("wb") as output:
                    shutil.copyfileobj(response, output)
            os.replace(temporary, archive)
        finally:
            temporary.unlink(missing_ok=True)
        return archive

    async def _install_binary_target(self, environment: Any, target: Any) -> None:
        remote_archive = "/tmp/karcarthy-harbor.tgz"
        remote_jre = "/tmp/karcarthy-jre.tar.gz"
        jre_archive = await asyncio.to_thread(
            self._jre_archive, self._platform_id
        )
        await environment.upload_file(
            source_path=self._local_archive_path,
            target_path=remote_archive,
        )
        await environment.upload_file(
            source_path=jre_archive,
            target_path=remote_jre,
        )
        await self.exec_as_root(
            environment,
            command=f"""
set -euo pipefail
rm -rf {self._BINARY_INSTALL_DIR}
mkdir -p {self._BINARY_INSTALL_DIR}/dist
tar -xzf {remote_archive} -C {self._BINARY_INSTALL_DIR}/dist
mkdir -p {self._BINARY_INSTALL_DIR}/jre
tar -xzf {remote_jre} -C {self._BINARY_INSTALL_DIR}/jre --strip-components=1
chmod -R a+rX {self._BINARY_INSTALL_DIR}
""".strip(),
        )

    async def install(self, environment: Any) -> None:
        """Install ACP runtime dependencies, the application, and a Java runtime."""
        await self._ensure_registry_entry()
        platform_id = await self._detect_platform(environment)
        self._platform_id = platform_id
        kind, target = self._select_distribution(platform_id)
        self._selected_distribution_kind = kind
        await self.exec_as_root(
            environment,
            command=self._build_dependencies_command(kind),
            env={"DEBIAN_FRONTEND": "noninteractive"},
        )
        await self._install_binary_target(environment, target)

        launcher_path = self.logs_dir / "acp-launch.sh"
        launcher_path.write_text(self._build_launcher_script(kind, target))
        await environment.upload_file(
            source_path=launcher_path,
            target_path=self._LAUNCHER_REMOTE_PATH,
        )
        await environment.exec(
            command=f"chmod +x {self._LAUNCHER_REMOTE_PATH}", user="root"
        )

        runner_path = Path(harbor_acp.__file__).with_name("acp_runner.py")
        await environment.upload_file(
            source_path=runner_path,
            target_path=self._RUNNER_REMOTE_PATH,
        )
        await environment.exec(
            command=f"chmod +x {self._RUNNER_REMOTE_PATH}", user="root"
        )
